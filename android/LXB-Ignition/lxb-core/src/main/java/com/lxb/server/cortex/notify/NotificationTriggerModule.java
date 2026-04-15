package com.lxb.server.cortex.notify;

import com.lxb.server.cortex.CortexTaskManager;
import com.lxb.server.cortex.LlmClient;
import com.lxb.server.cortex.TraceLogger;
import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Core-side notification trigger module.
 *
 * Lifecycle:
 * - poll dumpsys notification
 * - match rules
 * - optional llm condition + task rewrite
 * - submit new task into CortexTaskManager queue
 */
public class NotificationTriggerModule {

    private static final String TAG = "[LXB][NotifyTrigger]";
    private static final long DEFAULT_POLL_INTERVAL_MS = 2000L;
    private static final long MIN_POLL_INTERVAL_MS = 1000L;
    private static final long MAX_POLL_INTERVAL_MS = 15000L;
    private static final long CIRCUIT_OPEN_MS = 5 * 60 * 1000L;

    private static final String PROP_ENABLED = "lxb.notify.enabled";
    private static final String PROP_RULES_PATH = "lxb.notify.rules.path";
    private static final String PROP_LOGS_PATH = "lxb.notify.logs.path";
    private static final String PROP_POLL_INTERVAL_MS = "lxb.notify.poll.interval.ms";
    private static final String PROP_BASE_DIR = "lxb.notify.base.dir";

    private final CortexTaskManager taskManager;
    private final TraceLogger trace;
    private final NotificationRuleStore ruleStore;
    private final NotificationTriggerLogStore logStore;
    private final NotificationDumpParser dumpParser = new NotificationDumpParser();
    private final NotificationTaskRewriter taskRewriter = new NotificationTaskRewriter(new LlmClient());
    private final BlockingQueue<TriggerJob> triggerQueue = new LinkedBlockingQueue<TriggerJob>(200);

    private final ConcurrentHashMap<String, Long> ruleLastTriggeredMs = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, Integer> ruleFailStreak = new ConcurrentHashMap<String, Integer>();
    private final ConcurrentHashMap<String, Long> ruleCircuitUntilMs = new ConcurrentHashMap<String, Long>();
    private final Object ruleFileLock = new Object();

    private final long pollIntervalMs;
    private volatile boolean running = false;
    private volatile boolean pollerEnabled = true;
    private volatile boolean baselineReady = false;
    private volatile long lastPostTimeMs = 0L;
    private final Thread pollThread;
    private final Thread workerThread;

    public NotificationTriggerModule(CortexTaskManager taskManager, TraceLogger trace) {
        this.taskManager = taskManager;
        this.trace = trace;
        this.pollIntervalMs = resolvePollIntervalMs();
        this.ruleStore = new NotificationRuleStore(resolveRulesPath());
        this.logStore = new NotificationTriggerLogStore(resolveLogsPath(), 1000);

        this.pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "NotifyTriggerPoller");
        this.pollThread.setDaemon(true);

        this.workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                workerLoop();
            }
        }, "NotifyTriggerWorker");
        this.workerThread.setDaemon(true);

        boolean enabled = resolveEnabled();
        if (!enabled) {
            logEvent("notify_module_disabled", null, null, null, "disabled_by_property");
            return;
        }
        this.running = true;
        this.pollThread.start();
        this.workerThread.start();
        logEvent("notify_module_started", null, null, null, "poll_ms=" + pollIntervalMs);
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("running", running);
        out.put("poller_enabled", pollerEnabled);
        out.put("baseline_ready", baselineReady);
        out.put("last_post_time", lastPostTimeMs);
        out.put("queue_size", triggerQueue.size());
        out.put("rules_loaded", ruleStore.loadRules().size());
        out.put("rules_path", ruleStore.getFilePath());
        return out;
    }

    public List<Map<String, Object>> listRules() {
        List<NotificationTriggerRule> rules = ruleStore.loadRules();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rules.size());
        for (NotificationTriggerRule r : rules) {
            out.add(toRuleMap(r));
        }
        return out;
    }

    public Map<String, Object> upsertRule(Map<String, Object> ruleObj) {
        if (ruleObj == null) {
            return errOut("rule is required");
        }
        synchronized (ruleFileLock) {
            try {
                Map<String, Object> root = loadRuleRoot();
                @SuppressWarnings("unchecked")
                List<Object> rows = (List<Object>) root.get("rules");
                if (rows == null) {
                    rows = new ArrayList<Object>();
                    root.put("rules", rows);
                }

                int fallbackIndex = rows.size() + 1;
                NotificationTriggerRule parsed = NotificationTriggerRule.fromMap(ruleObj, fallbackIndex);
                if (parsed == null || parsed.id.isEmpty()) {
                    return errOut("invalid rule");
                }
                if (parsed.packageList == null || parsed.packageList.isEmpty()) {
                    return errOut("package is required");
                }
                Map<String, Object> normalized = toRuleMap(parsed);
                normalized.put("package_mode", "allowlist");

                boolean updated = false;
                for (int i = 0; i < rows.size(); i++) {
                    Object o = rows.get(i);
                    if (!(o instanceof Map)) continue;
                    String id = stringOrEmpty(((Map<?, ?>) o).get("id"));
                    if (parsed.id.equals(id)) {
                        rows.set(i, normalized);
                        updated = true;
                        break;
                    }
                }
                if (!updated) {
                    rows.add(normalized);
                }
                root.put("schema_version", 1);
                root.put("updated_at", System.currentTimeMillis());
                saveRuleRoot(root);
                ruleStore.invalidateCache();

                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", true);
                out.put("updated", updated);
                out.put("rule", normalized);
                return out;
            } catch (Exception e) {
                return errOut(String.valueOf(e));
            }
        }
    }

    public Map<String, Object> removeRule(String ruleId) {
        String id = ruleId != null ? ruleId.trim() : "";
        if (id.isEmpty()) {
            return errOut("rule_id is required");
        }
        synchronized (ruleFileLock) {
            try {
                Map<String, Object> root = loadRuleRoot();
                @SuppressWarnings("unchecked")
                List<Object> rows = (List<Object>) root.get("rules");
                if (rows == null || rows.isEmpty()) {
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("ok", true);
                    out.put("removed", false);
                    out.put("rule_id", id);
                    return out;
                }
                boolean removed = false;
                for (int i = rows.size() - 1; i >= 0; i--) {
                    Object o = rows.get(i);
                    if (!(o instanceof Map)) continue;
                    String rid = stringOrEmpty(((Map<?, ?>) o).get("id"));
                    if (id.equals(rid)) {
                        rows.remove(i);
                        removed = true;
                    }
                }
                root.put("schema_version", 1);
                root.put("updated_at", System.currentTimeMillis());
                saveRuleRoot(root);
                ruleStore.invalidateCache();

                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", true);
                out.put("removed", removed);
                out.put("rule_id", id);
                return out;
            } catch (Exception e) {
                return errOut(String.valueOf(e));
            }
        }
    }

    public List<Map<String, Object>> listLogs(int limit) {
        int effective = limit > 0 ? limit : 100;
        return logStore.listRecent(effective);
    }

    public Map<String, Object> control(String actionRaw) {
        String action = actionRaw != null ? actionRaw.trim().toLowerCase(Locale.ROOT) : "";
        if (action.isEmpty() || "status".equals(action)) {
            Map<String, Object> out = getStatus();
            out.put("ok", true);
            return out;
        }
        if ("start".equals(action) || "enable".equals(action)) {
            pollerEnabled = true;
            baselineReady = false;
            Map<String, Object> out = getStatus();
            out.put("ok", true);
            out.put("action", action);
            return out;
        }
        if ("stop".equals(action) || "disable".equals(action)) {
            pollerEnabled = false;
            Map<String, Object> out = getStatus();
            out.put("ok", true);
            out.put("action", action);
            return out;
        }
        if ("reload".equals(action)) {
            ruleStore.invalidateCache();
            Map<String, Object> out = getStatus();
            out.put("ok", true);
            out.put("action", action);
            return out;
        }
        return errOut("unsupported control action: " + action);
    }

    private void pollLoop() {
        long failBackoffMs = pollIntervalMs;
        while (running) {
            try {
                if (!pollerEnabled) {
                    sleepQuietly(pollIntervalMs);
                    continue;
                }
                List<NotificationTriggerRule> rules = ruleStore.loadRules();
                List<NotificationTriggerRule> enabledRules = filterEnabledRules(rules);
                if (enabledRules.isEmpty()) {
                    sleepQuietly(pollIntervalMs);
                    continue;
                }

                ShellResult dump = collectNotificationDump();
                if (!dump.ok) {
                    logEvent("notify_dump_failed", null, null, null, dump.error);
                    failBackoffMs = nextBackoff(failBackoffMs);
                    sleepQuietly(failBackoffMs);
                    continue;
                }

                long now = System.currentTimeMillis();
                List<NotificationEvent> items = dumpParser.parse(dump.output, now);
                if (items.isEmpty()) {
                    failBackoffMs = pollIntervalMs;
                    sleepQuietly(pollIntervalMs);
                    continue;
                }
                Collections.sort(items, new Comparator<NotificationEvent>() {
                    @Override
                    public int compare(NotificationEvent a, NotificationEvent b) {
                        return Long.compare(a.postTime, b.postTime);
                    }
                });

                long roundMax = lastPostTimeMs;
                if (!baselineReady) {
                    for (NotificationEvent e : items) {
                        if (e.postTime > roundMax) {
                            roundMax = e.postTime;
                        }
                    }
                    lastPostTimeMs = roundMax;
                    baselineReady = true;
                    logEvent("notify_baseline_ready", null, null, null, "last_post_time=" + lastPostTimeMs);
                    failBackoffMs = pollIntervalMs;
                    sleepQuietly(pollIntervalMs);
                    continue;
                }

                for (NotificationEvent e : items) {
                    if (e.postTime <= lastPostTimeMs) {
                        continue;
                    }
                    if (e.postTime > roundMax) {
                        roundMax = e.postTime;
                    }

                    long nowTs = System.currentTimeMillis();
                    List<NotificationTriggerRule> candidates = collectCandidateRules(enabledRules, e, nowTs);
                    if (candidates.isEmpty()) {
                        continue;
                    }

                    boolean offered = triggerQueue.offer(new TriggerJob(e, candidates), 100, TimeUnit.MILLISECONDS);
                    if (!offered) {
                        logEvent("notify_queue_full_drop", e, null, null, "queue_full");
                    }
                }

                lastPostTimeMs = roundMax;
                failBackoffMs = pollIntervalMs;
                sleepQuietly(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logEvent("notify_poll_loop_error", null, null, null, String.valueOf(e));
                failBackoffMs = nextBackoff(failBackoffMs);
                sleepQuietly(failBackoffMs);
            }
        }
    }

    private void workerLoop() {
        while (running) {
            try {
                TriggerJob job = triggerQueue.take();
                processTriggerJob(job);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logEvent("notify_worker_loop_error", null, null, null, String.valueOf(e));
            }
        }
    }

    private void processTriggerJob(TriggerJob job) {
        if (job == null || job.event == null || job.rules == null || job.rules.isEmpty()) {
            return;
        }
        NotificationEvent event = job.event;
        for (NotificationTriggerRule rule : job.rules) {
            if (rule == null) continue;
            long now = System.currentTimeMillis();
            if (isCircuitOpen(rule.id, now)) {
                logEvent("notify_rule_circuit_open", event, rule, null, "skip");
                continue;
            }
            if (isInCooldown(rule.id, now, rule.cooldownMs)) {
                logEvent("notify_rule_cooldown", event, rule, null, "skip");
                continue;
            }

            if (rule.llmConditionEnabled && !rule.llmCondition.isEmpty()) {
                NotificationTaskRewriter.ConditionResult cond = taskRewriter.evaluateCondition(rule, event);
                if (!cond.match) {
                    logEvent("notify_llm_condition_no", event, rule, cond.raw, cond.error);
                    continue;
                }
            }

            String baseTask = pickBaseTask(rule);
            String finalTask = baseTask;
            String rewriteRaw = "";
            if (rule.taskRewriteEnabled) {
                NotificationTaskRewriter.RewriteResult rewrite =
                        taskRewriter.rewriteTask(
                                baseTask,
                                event,
                                rule.action != null ? rule.action.packageName : ""
                        );
                rewriteRaw = rewrite.raw;
                if (rewrite.ok) {
                    finalTask = rewrite.finalTask;
                } else if ("skip".equals(rule.taskRewriteFailPolicy)) {
                    logEvent("notify_rewrite_skip", event, rule, rewriteRaw, rewrite.error);
                    continue;
                } else {
                    finalTask = baseTask;
                    logEvent("notify_rewrite_fallback", event, rule, rewriteRaw, rewrite.error);
                }
            }
            finalTask = finalTask != null ? finalTask.trim() : "";
            if (finalTask.isEmpty()) {
                recordRuleFailure(rule.id);
                logEvent("notify_empty_final_task", event, rule, rewriteRaw, "empty_final_task");
                continue;
            }

            try {
                String userPlaybook = mergePlaybook(rule.action.userPlaybook, event);
                String taskId = taskManager.submitNotificationTriggeredTask(
                        finalTask,
                        rule.action.packageName,
                        userPlaybook,
                        Boolean.valueOf(rule.action.recordEnabled),
                        rule.action.useMapOverride,
                        rule.action.taskMapMode,
                        rule.id,
                        buildRuleConfigHash(rule)
                );
                ruleLastTriggeredMs.put(rule.id, now);
                clearRuleFailure(rule.id);
                Map<String, Object> extra = new LinkedHashMap<String, Object>();
                extra.put("task_id", taskId);
                extra.put("final_task", finalTask);
                logEvent("notify_task_submitted", event, rule, rewriteRaw, null, extra);
                if (rule.stopAfterMatched) {
                    return;
                }
            } catch (Exception e) {
                recordRuleFailure(rule.id);
                logEvent("notify_task_submit_failed", event, rule, rewriteRaw, String.valueOf(e));
            }
        }
    }

    private List<NotificationTriggerRule> collectCandidateRules(
            List<NotificationTriggerRule> rules,
            NotificationEvent event,
            long nowMs
    ) {
        List<NotificationTriggerRule> out = new ArrayList<NotificationTriggerRule>();
        for (NotificationTriggerRule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            if (!matchPackage(rule, event.packageName)) continue;
            if (!matchText(rule, event)) continue;
            if (!matchActiveTime(rule, nowMs)) continue;
            if (isCircuitOpen(rule.id, nowMs)) continue;
            if (isInCooldown(rule.id, nowMs, rule.cooldownMs)) continue;
            out.add(rule);
        }
        return out;
    }

    private boolean matchPackage(NotificationTriggerRule rule, String pkg) {
        String packageName = pkg != null ? pkg.trim() : "";
        if (packageName.isEmpty()) return false;
        if (rule == null || rule.packageList == null || rule.packageList.isEmpty()) return false;

        for (String p : rule.packageList) {
            if (packageName.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchText(NotificationTriggerRule rule, NotificationEvent event) {
        String title = event.title != null ? event.title : "";
        String body = event.text != null ? event.text : "";
        if ("regex".equals(rule.textMode)) {
            return matchRegex(rule.titlePattern, title) && matchRegex(rule.bodyPattern, body);
        }
        return matchContains(rule.titlePattern, title) && matchContains(rule.bodyPattern, body);
    }

    private boolean matchContains(String pattern, String value) {
        String p = pattern != null ? pattern.trim() : "";
        if (p.isEmpty()) return true;
        String v = value != null ? value : "";
        return v.contains(p);
    }

    private boolean matchRegex(String pattern, String value) {
        String p = pattern != null ? pattern.trim() : "";
        if (p.isEmpty()) return true;
        try {
            return Pattern.compile(p).matcher(value != null ? value : "").find();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean matchActiveTime(NotificationTriggerRule rule, long nowMs) {
        if (rule == null) return true;
        String start = rule.activeTimeStart != null ? rule.activeTimeStart.trim() : "";
        String end = rule.activeTimeEnd != null ? rule.activeTimeEnd.trim() : "";
        if (start.isEmpty() || end.isEmpty()) {
            // If either side is empty, treat as "always active".
            return true;
        }
        Integer startMinute = parseMinutesOfDay(start);
        Integer endMinute = parseMinutesOfDay(end);
        if (startMinute == null || endMinute == null) {
            return true;
        }
        int nowMinute = currentMinutesOfDay(nowMs);
        if (startMinute.intValue() <= endMinute.intValue()) {
            return nowMinute >= startMinute.intValue() && nowMinute <= endMinute.intValue();
        }
        // Cross-midnight window, e.g. 23:00-06:00.
        return nowMinute >= startMinute.intValue() || nowMinute <= endMinute.intValue();
    }

    private Integer parseMinutesOfDay(String hhmm) {
        if (hhmm == null) return null;
        String s = hhmm.trim();
        int colon = s.indexOf(':');
        if (colon <= 0 || colon >= s.length() - 1) return null;
        try {
            int hour = Integer.parseInt(s.substring(0, colon).trim());
            int minute = Integer.parseInt(s.substring(colon + 1).trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return Integer.valueOf(hour * 60 + minute);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int currentMinutesOfDay(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private boolean isInCooldown(String ruleId, long now, long cooldownMs) {
        if (cooldownMs <= 0L || ruleId == null || ruleId.isEmpty()) return false;
        Long last = ruleLastTriggeredMs.get(ruleId);
        return last != null && now - last.longValue() < cooldownMs;
    }

    private boolean isCircuitOpen(String ruleId, long now) {
        if (ruleId == null || ruleId.isEmpty()) return false;
        Long until = ruleCircuitUntilMs.get(ruleId);
        return until != null && now < until.longValue();
    }

    private void recordRuleFailure(String ruleId) {
        if (ruleId == null || ruleId.isEmpty()) return;
        int next = 1;
        Integer prev = ruleFailStreak.get(ruleId);
        if (prev != null) {
            next = prev.intValue() + 1;
        }
        if (next >= 3) {
            ruleCircuitUntilMs.put(ruleId, System.currentTimeMillis() + CIRCUIT_OPEN_MS);
            ruleFailStreak.remove(ruleId);
        } else {
            ruleFailStreak.put(ruleId, Integer.valueOf(next));
        }
    }

    private void clearRuleFailure(String ruleId) {
        if (ruleId == null || ruleId.isEmpty()) return;
        ruleFailStreak.remove(ruleId);
        ruleCircuitUntilMs.remove(ruleId);
    }

    private String pickBaseTask(NotificationTriggerRule rule) {
        String fromRewrite = rule.taskRewriteInstruction != null ? rule.taskRewriteInstruction.trim() : "";
        if (!fromRewrite.isEmpty()) return fromRewrite;
        if (rule.action != null && rule.action.userTask != null) {
            return rule.action.userTask.trim();
        }
        return "";
    }

    private String mergePlaybook(String base, NotificationEvent e) {
        String b = base != null ? base.trim() : "";
        StringBuilder sb = new StringBuilder();
        if (!b.isEmpty()) {
            sb.append(b).append("\n");
        }
        sb.append("trigger_notification:\n");
        sb.append("package=").append(e.packageName).append("\n");
        sb.append("title=").append(e.title).append("\n");
        sb.append("text=").append(e.text).append("\n");
        sb.append("ticker=").append(e.ticker).append("\n");
        sb.append("post_time_ms=").append(e.postTime);
        return sb.toString();
    }

    private List<NotificationTriggerRule> filterEnabledRules(List<NotificationTriggerRule> rules) {
        if (rules == null || rules.isEmpty()) return Collections.emptyList();
        List<NotificationTriggerRule> out = new ArrayList<NotificationTriggerRule>();
        for (NotificationTriggerRule r : rules) {
            if (r != null && r.enabled) {
                out.add(r);
            }
        }
        return out;
    }

    private ShellResult collectNotificationDump() {
        ShellResult first = runShellCommand("dumpsys notification --noredact", 5000);
        if (first.ok && first.output != null && !first.output.trim().isEmpty()) {
            return first;
        }
        return runShellCommand("dumpsys notification", 5000);
    }

    private ShellResult runShellCommand(String command, long timeoutMs) {
        Process process = null;
        StreamCollector collector = null;
        try {
            process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            collector = new StreamCollector(process.getInputStream());
            collector.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                collector.join(400);
                return ShellResult.error("timeout");
            }
            collector.join(400);
            int exit = process.exitValue();
            String output = collector.getText();
            if (exit != 0) {
                return ShellResult.error("exit=" + exit + ", out=" + crop(output, 180));
            }
            return ShellResult.ok(output);
        } catch (Exception e) {
            return ShellResult.error(String.valueOf(e));
        } finally {
            if (collector != null && collector.isAlive()) {
                collector.interrupt();
            }
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean resolveEnabled() {
        String raw = System.getProperty(PROP_ENABLED);
        if (raw == null || raw.trim().isEmpty()) {
            return true;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return !"0".equals(s) && !"false".equals(s) && !"off".equals(s) && !"no".equals(s);
    }

    private long resolvePollIntervalMs() {
        String raw = System.getProperty(PROP_POLL_INTERVAL_MS);
        long ms = parseLong(raw, DEFAULT_POLL_INTERVAL_MS);
        if (ms < MIN_POLL_INTERVAL_MS) ms = MIN_POLL_INTERVAL_MS;
        if (ms > MAX_POLL_INTERVAL_MS) ms = MAX_POLL_INTERVAL_MS;
        return ms;
    }

    private String resolveRulesPath() {
        String override = System.getProperty(PROP_RULES_PATH);
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return new File(resolveBaseDir(), "notify_rules.v1.json").getAbsolutePath();
    }

    private String resolveLogsPath() {
        String override = System.getProperty(PROP_LOGS_PATH);
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return new File(resolveBaseDir(), "notify_logs.v1.json").getAbsolutePath();
    }

    private String resolveBaseDir() {
        String custom = System.getProperty(PROP_BASE_DIR);
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim();
        }
        String taskMemoryPath = System.getProperty("lxb.task.memory.path");
        if (taskMemoryPath != null && !taskMemoryPath.trim().isEmpty()) {
            File p = new File(taskMemoryPath.trim()).getParentFile();
            if (p != null) {
                return p.getAbsolutePath();
            }
        }
        return "/data/local/tmp/lxb";
    }

    private static long parseLong(String raw, long defVal) {
        if (raw == null || raw.trim().isEmpty()) return defVal;
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }

    private long nextBackoff(long current) {
        long c = Math.max(MIN_POLL_INTERVAL_MS, current);
        long n = c * 2L;
        return Math.min(n, MAX_POLL_INTERVAL_MS);
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0L) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void logEvent(String type, NotificationEvent event, NotificationTriggerRule rule, String raw, String error) {
        logEvent(type, event, rule, raw, error, null);
    }

    private void logEvent(
            String type,
            NotificationEvent event,
            NotificationTriggerRule rule,
            String raw,
            String error,
            Map<String, Object> extra
    ) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("ts", System.currentTimeMillis());
        row.put("event", type);
        if (event != null) {
            row.put("package", event.packageName);
            row.put("post_time", event.postTime);
            row.put("title", crop(event.title, 80));
            row.put("text", crop(event.text, 120));
        }
        if (rule != null) {
            row.put("rule_id", rule.id);
            row.put("rule_name", rule.name);
        }
        if (raw != null && !raw.trim().isEmpty()) {
            row.put("raw", crop(raw, 300));
        }
        if (error != null && !error.trim().isEmpty()) {
            row.put("error", crop(error, 300));
        }
        if (extra != null && !extra.isEmpty()) {
            row.putAll(extra);
        }
        logStore.append(row);

        Map<String, Object> traceFields = new LinkedHashMap<String, Object>(row);
        traceFields.put("task_id", "");
        trace.event("notify_trigger", traceFields);
    }

    private static String crop(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "...";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRuleRoot() throws Exception {
        File f = new File(ruleStore.getFilePath());
        if (!f.exists() || f.length() <= 0L) {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("schema_version", 1);
            root.put("rules", new ArrayList<Object>());
            return root;
        }
        byte[] data = readAllBytes(f, 4 * 1024 * 1024);
        if (data.length == 0) {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("schema_version", 1);
            root.put("rules", new ArrayList<Object>());
            return root;
        }
        Object parsed = Json.parse(new String(data, StandardCharsets.UTF_8));
        if (parsed instanceof Map) {
            Map<String, Object> root = (Map<String, Object>) parsed;
            Object rulesObj = root.get("rules");
            if (!(rulesObj instanceof List)) {
                root.put("rules", new ArrayList<Object>());
            }
            return root;
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema_version", 1);
        root.put("rules", new ArrayList<Object>());
        return root;
    }

    private void saveRuleRoot(Map<String, Object> root) throws Exception {
        File f = new File(ruleStore.getFilePath());
        File p = f.getParentFile();
        if (p != null && !p.exists()) {
            p.mkdirs();
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(Json.stringify(root).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static byte[] readAllBytes(File f, int maxBytes) throws Exception {
        FileInputStream in = null;
        ByteArrayOutputStream out = null;
        try {
            in = new FileInputStream(f);
            out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total >= maxBytes) break;
            }
            return out.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Map<String, Object> toRuleMap(NotificationTriggerRule r) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", r.id);
        m.put("name", r.name);
        m.put("enabled", r.enabled);
        m.put("priority", r.priority);
        m.put("package_mode", r.packageMode);
        m.put("package_list", new ArrayList<String>(r.packageList));
        m.put("text_mode", r.textMode);
        m.put("title_pattern", r.titlePattern);
        m.put("body_pattern", r.bodyPattern);
        m.put("llm_condition_enabled", r.llmConditionEnabled);
        m.put("llm_condition", r.llmCondition);
        m.put("llm_yes_token", r.llmYesToken);
        m.put("llm_no_token", r.llmNoToken);
        m.put("llm_timeout_ms", r.llmTimeoutMs);
        m.put("task_rewrite_enabled", r.taskRewriteEnabled);
        m.put("task_rewrite_instruction", r.taskRewriteInstruction);
        m.put("task_rewrite_timeout_ms", r.taskRewriteTimeoutMs);
        m.put("task_rewrite_fail_policy", r.taskRewriteFailPolicy);
        m.put("cooldown_ms", r.cooldownMs);
        m.put("active_time_start", r.activeTimeStart);
        m.put("active_time_end", r.activeTimeEnd);
        m.put("stop_after_matched", r.stopAfterMatched);
        Map<String, Object> action = new LinkedHashMap<String, Object>();
        action.put("type", r.action.type);
        action.put("user_task", r.action.userTask);
        action.put("package", r.action.packageName);
        action.put("user_playbook", r.action.userPlaybook);
        action.put("record_enabled", r.action.recordEnabled);
        if (r.action.useMapOverride != null) {
            action.put("use_map", r.action.useMapOverride.booleanValue());
        }
        action.put("task_map_mode", r.action.taskMapMode);
        m.put("action", action);
        return m;
    }

    private String buildRuleConfigHash(NotificationTriggerRule r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("package_mode=").append(stringOrEmpty(r.packageMode)).append('\n');
        sb.append("package_list=").append(String.valueOf(r.packageList)).append('\n');
        sb.append("text_mode=").append(stringOrEmpty(r.textMode)).append('\n');
        sb.append("title_pattern=").append(stringOrEmpty(r.titlePattern)).append('\n');
        sb.append("body_pattern=").append(stringOrEmpty(r.bodyPattern)).append('\n');
        sb.append("llm_condition_enabled=").append(r.llmConditionEnabled).append('\n');
        sb.append("llm_condition=").append(stringOrEmpty(r.llmCondition)).append('\n');
        sb.append("action_user_task=").append(stringOrEmpty(r.action.userTask)).append('\n');
        sb.append("action_package=").append(stringOrEmpty(r.action.packageName)).append('\n');
        sb.append("action_user_playbook=").append(stringOrEmpty(r.action.userPlaybook)).append('\n');
        sb.append("action_record_enabled=").append(r.action.recordEnabled).append('\n');
        sb.append("action_use_map=").append(r.action.useMapOverride != null ? r.action.useMapOverride.booleanValue() : true).append('\n');
        sb.append("action_task_map_mode=").append(stringOrEmpty(r.action.taskMapMode)).append('\n');
        return com.lxb.server.cortex.taskmap.TaskRouteKey.sha256Hex(sb.toString());
    }

    private Map<String, Object> errOut(String err) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("err", err != null ? err : "unknown_error");
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static final class TriggerJob {
        final NotificationEvent event;
        final List<NotificationTriggerRule> rules;

        TriggerJob(NotificationEvent event, List<NotificationTriggerRule> rules) {
            this.event = event;
            this.rules = rules;
        }
    }

    private static final class ShellResult {
        final boolean ok;
        final String output;
        final String error;

        ShellResult(boolean ok, String output, String error) {
            this.ok = ok;
            this.output = output != null ? output : "";
            this.error = error != null ? error : "";
        }

        static ShellResult ok(String output) {
            return new ShellResult(true, output, "");
        }

        static ShellResult error(String error) {
            return new ShellResult(false, "", error);
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        StreamCollector(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            if (in == null) return;
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }

        String getText() {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

}
