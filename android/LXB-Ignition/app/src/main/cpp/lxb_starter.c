#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define RESULT_OK "RESULT:OK"
#define RESULT_ERR "RESULT:ERR"

typedef struct {
    char action[16];
    char jar[512];
    char main_class[256];
    char process[128];
    char port[16];
    char log_path[512];
    char map_dir[512];
    char llm_config_path[512];
    char task_memory_path[512];
    char app_labels_path[512];
} Config;

static void print_err(const char *code, const char *fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    for (size_t i = 0; i < strlen(msg); i++) {
        if (msg[i] == '\n' || msg[i] == '\r') msg[i] = ' ';
    }
    printf(RESULT_ERR " CODE=%s MSG=%s\n", code ? code : "unknown", msg);
    fflush(stdout);
}

static void print_ok(const char *fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    printf(RESULT_OK " %s\n", msg);
    fflush(stdout);
}

static bool is_digits(const char *s) {
    if (!s || !*s) return false;
    for (const char *p = s; *p; ++p) {
        if (!isdigit((unsigned char) *p)) return false;
    }
    return true;
}

static bool parse_port(const char *s, int *out_port) {
    if (!s || !*s || !is_digits(s)) return false;
    long v = strtol(s, NULL, 10);
    if (v < 1 || v > 65535) return false;
    if (out_port) *out_port = (int) v;
    return true;
}

static int read_cmdline_token(int pid, char *out, size_t out_size) {
    if (!out || out_size == 0) return -1;
    out[0] = '\0';
    char path[128];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, out, out_size - 1);
    close(fd);
    if (n <= 0) return -1;
    out[n] = '\0';
    for (ssize_t i = 0; i < n; i++) {
        if (out[i] == '\0') {
            out[i] = '\0';
            break;
        }
    }
    return 0;
}

static int read_cmdline_raw(int pid, char *out, size_t out_size, ssize_t *read_len) {
    if (!out || out_size == 0) return -1;
    out[0] = '\0';
    char path[128];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, out, out_size - 1);
    close(fd);
    if (n <= 0) return -1;
    out[n] = '\0';
    if (read_len) *read_len = n;
    return 0;
}

static bool cmdline_has_token(const char *raw, ssize_t n, const char *token) {
    if (!raw || n <= 0 || !token || !*token) return false;
    ssize_t i = 0;
    while (i < n) {
        const char *arg = raw + i;
        size_t len = strlen(arg);
        if (len == 0) {
            i++;
            continue;
        }
        if (strcmp(arg, token) == 0) return true;
        i += (ssize_t) len + 1;
    }
    return false;
}

static bool is_target_pid(int pid, const char *process_name, const char *main_class) {
    if (!process_name || !*process_name) return false;
    char cmd[512];
    if (read_cmdline_token(pid, cmd, sizeof(cmd)) != 0) return false;
    if (strcmp(cmd, process_name) != 0) return false;
    if (!main_class || !*main_class) return true;

    char raw[4096];
    ssize_t n = 0;
    if (read_cmdline_raw(pid, raw, sizeof(raw), &n) != 0) return false;
    return cmdline_has_token(raw, n, main_class);
}

static bool pid_alive(int pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    struct stat st;
    return stat(path, &st) == 0;
}

static bool kill_pid_force(int pid) {
    if (pid <= 1) return false;
    kill(pid, SIGTERM);
    for (int i = 0; i < 8; i++) {
        usleep(120 * 1000);
        if (!pid_alive(pid)) return true;
    }
    kill(pid, SIGKILL);
    for (int i = 0; i < 8; i++) {
        usleep(120 * 1000);
        if (!pid_alive(pid)) return true;
    }
    return !pid_alive(pid);
}

static int kill_by_process_name(const char *process_name, const char *main_class) {
    if (!process_name || !*process_name) return 0;
    DIR *dir = opendir("/proc");
    if (!dir) return 0;
    int self = getpid();
    int killed = 0;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        if (!is_digits(ent->d_name)) continue;
        int pid = atoi(ent->d_name);
        if (pid <= 1 || pid == self) continue;
        if (!is_target_pid(pid, process_name, main_class)) continue;
        if (kill_pid_force(pid)) killed++;
    }
    closedir(dir);
    return killed;
}

static const char *pick_app_process_binary(void) {
    // Prefer ABI-matching app_process variant when available.
    const char *candidates_64[] = {
            "/system/bin/app_process64",
            "/system/bin/app_process",
            "/system/bin/app_process32"
    };
    const char *candidates_32[] = {
            "/system/bin/app_process32",
            "/system/bin/app_process",
            "/system/bin/app_process64"
    };
    const char **list = (sizeof(void *) == 8) ? candidates_64 : candidates_32;
    for (size_t i = 0; i < 3; i++) {
        if (access(list[i], X_OK) == 0) return list[i];
    }
    return NULL;
}

static bool can_read_file(const char *path) {
    return path && *path && access(path, R_OK) == 0;
}

static int parse_args(int argc, char **argv, Config *cfg) {
    if (!cfg) return -1;
    memset(cfg, 0, sizeof(*cfg));
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--action") == 0 && i + 1 < argc) {
            snprintf(cfg->action, sizeof(cfg->action), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--jar") == 0 && i + 1 < argc) {
            snprintf(cfg->jar, sizeof(cfg->jar), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--main") == 0 && i + 1 < argc) {
            snprintf(cfg->main_class, sizeof(cfg->main_class), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--process") == 0 && i + 1 < argc) {
            snprintf(cfg->process, sizeof(cfg->process), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--port") == 0 && i + 1 < argc) {
            snprintf(cfg->port, sizeof(cfg->port), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--log") == 0 && i + 1 < argc) {
            snprintf(cfg->log_path, sizeof(cfg->log_path), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--map-dir") == 0 && i + 1 < argc) {
            snprintf(cfg->map_dir, sizeof(cfg->map_dir), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--llm-config") == 0 && i + 1 < argc) {
            snprintf(cfg->llm_config_path, sizeof(cfg->llm_config_path), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--task-memory") == 0 && i + 1 < argc) {
            snprintf(cfg->task_memory_path, sizeof(cfg->task_memory_path), "%s", argv[++i]);
        } else if (strcmp(argv[i], "--app-labels") == 0 && i + 1 < argc) {
            snprintf(cfg->app_labels_path, sizeof(cfg->app_labels_path), "%s", argv[++i]);
        }
    }
    if (cfg->process[0] == '\0') snprintf(cfg->process, sizeof(cfg->process), "lxb_core");
    if (cfg->log_path[0] == '\0') snprintf(cfg->log_path, sizeof(cfg->log_path), "/data/local/tmp/lxb-core.log");
    return 0;
}

static int start_core(const Config *cfg) {
    if (cfg->jar[0] == '\0' || cfg->main_class[0] == '\0' || cfg->port[0] == '\0') {
        print_err("missing_args", "start requires --jar --main --port");
        return 2;
    }
    if (!can_read_file(cfg->jar)) {
        print_err("jar_unreadable", "jar not readable: %s", cfg->jar);
        return 2;
    }
    int port = 0;
    if (!parse_port(cfg->port, &port)) {
        print_err("invalid_port", "port must be 1..65535, got: %s", cfg->port);
        return 2;
    }
    const char *app_process_bin = pick_app_process_binary();
    if (!app_process_bin) {
        print_err("missing_app_process", "no executable app_process found");
        return 2;
    }

    kill_by_process_name(cfg->process, cfg->main_class);
    usleep(250 * 1000);

    int exec_pipe[2];
    if (pipe(exec_pipe) != 0) {
        print_err("pipe_failed", "errno=%d", errno);
        return 3;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(exec_pipe[0]);
        close(exec_pipe[1]);
        print_err("fork_failed", "errno=%d", errno);
        return 3;
    }
    if (pid == 0) {
        close(exec_pipe[0]);
        int flags = fcntl(exec_pipe[1], F_GETFD);
        if (flags >= 0) {
            fcntl(exec_pipe[1], F_SETFD, flags | FD_CLOEXEC);
        }
        setsid();
        chdir("/");

        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
        }

        int logfd = open(cfg->log_path, O_CREAT | O_WRONLY | O_APPEND, 0644);
        if (logfd >= 0) {
            dup2(logfd, STDOUT_FILENO);
            dup2(logfd, STDERR_FILENO);
        } else if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
        }

        if (logfd > 2) close(logfd);
        if (devnull > 2) close(devnull);

        setenv("CLASSPATH", cfg->jar, 1);

        char classpath_arg[700];
        char nice_arg[200];
        char map_dir_arg[700];
        char llm_cfg_arg[700];
        char task_mem_arg[700];
        char app_labels_arg[700];
        snprintf(classpath_arg, sizeof(classpath_arg), "-Djava.class.path=%s", cfg->jar);
        snprintf(nice_arg, sizeof(nice_arg), "--nice-name=%s", cfg->process);
        if (cfg->map_dir[0] != '\0') {
            snprintf(map_dir_arg, sizeof(map_dir_arg), "-Dlxb.map.dir=%s", cfg->map_dir);
        } else {
            map_dir_arg[0] = '\0';
        }
        if (cfg->llm_config_path[0] != '\0') {
            snprintf(llm_cfg_arg, sizeof(llm_cfg_arg), "-Dlxb.llm.config.path=%s", cfg->llm_config_path);
        } else {
            llm_cfg_arg[0] = '\0';
        }
        if (cfg->task_memory_path[0] != '\0') {
            snprintf(task_mem_arg, sizeof(task_mem_arg), "-Dlxb.task.memory.path=%s", cfg->task_memory_path);
        } else {
            task_mem_arg[0] = '\0';
        }
        if (cfg->app_labels_path[0] != '\0') {
            snprintf(app_labels_arg, sizeof(app_labels_arg), "-Dlxb.app.labels.path=%s", cfg->app_labels_path);
        } else {
            app_labels_arg[0] = '\0';
        }

        char *child_argv[16];
        int argi = 0;
        child_argv[argi++] = (char *) app_process_bin;
        child_argv[argi++] = classpath_arg;
        if (map_dir_arg[0] != '\0') child_argv[argi++] = map_dir_arg;
        if (llm_cfg_arg[0] != '\0') child_argv[argi++] = llm_cfg_arg;
        if (task_mem_arg[0] != '\0') child_argv[argi++] = task_mem_arg;
        if (app_labels_arg[0] != '\0') child_argv[argi++] = app_labels_arg;
        child_argv[argi++] = "/system/bin";
        child_argv[argi++] = nice_arg;
        child_argv[argi++] = (char *) cfg->main_class;
        child_argv[argi++] = (char *) cfg->port;
        child_argv[argi] = NULL;
        execvp(child_argv[0], child_argv);
        int exec_errno = errno;
        (void) write(exec_pipe[1], &exec_errno, sizeof(exec_errno));
        close(exec_pipe[1]);
        _exit(111);
    }

    close(exec_pipe[1]);
    int child_exec_errno = 0;
    ssize_t rn = read(exec_pipe[0], &child_exec_errno, sizeof(child_exec_errno));
    close(exec_pipe[0]);
    if (rn == (ssize_t) sizeof(child_exec_errno)) {
        (void) waitpid(pid, NULL, 0);
        print_err(
                "exec_failed",
                "app_process=%s errno=%d",
                app_process_bin,
                child_exec_errno
        );
        return 4;
    }

    usleep(200 * 1000);
    if (!pid_alive((int) pid)) {
        print_err("child_exited", "pid=%d", (int) pid);
        return 4;
    }
    print_ok(
            "ACTION=start PID=%d PROCESS=%s PORT=%d APP_PROCESS=%s",
            (int) pid,
            cfg->process,
            port,
            app_process_bin
    );
    return 0;
}

static int stop_core(const Config *cfg) {
    int killed = kill_by_process_name(cfg->process, cfg->main_class);
    print_ok("ACTION=stop PROCESS=%s KILLED=%d", cfg->process, killed);
    return 0;
}

int main(int argc, char **argv) {
    uid_t uid = getuid();
    if (uid != 0 && uid != 2000) {
        print_err("invalid_uid", "starter must run as root or shell, uid=%d", (int) uid);
        return 2;
    }

    Config cfg;
    parse_args(argc, argv, &cfg);
    if (cfg.action[0] == '\0') {
        print_err("missing_action", "use --action start|stop");
        return 2;
    }
    if (strcmp(cfg.action, "start") == 0) {
        return start_core(&cfg);
    }
    if (strcmp(cfg.action, "stop") == 0) {
        return stop_core(&cfg);
    }
    print_err("invalid_action", "%s", cfg.action);
    return 2;
}
