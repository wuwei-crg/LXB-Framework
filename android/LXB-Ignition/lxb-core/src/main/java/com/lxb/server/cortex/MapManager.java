package com.lxb.server.cortex;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal on-device map storage.
 *
 * Current runtime (app_process + /data/local/tmp) has no Android Context here,
 * so we store maps under a writable tmp directory.
 *
 * Layout:
 *   {baseDir}/{package}/nav_map.json      (current)
 *   {baseDir}/{package}/nav_map.bak.json  (backup)
 */
public class MapManager {

    private static final String LANE_ROOT_DIR = "_lane";
    private final File baseDir;

    public MapManager() {
        this(getDefaultBaseDir());
    }

    public MapManager(File baseDir) {
        this.baseDir = baseDir;
        if (!this.baseDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            this.baseDir.mkdirs();
        }
    }

    public File getCurrentMapFile(String pkg) {
        return new File(getPkgDir(pkg), "nav_map.json");
    }

    public File getBackupMapFile(String pkg) {
        return new File(getPkgDir(pkg), "nav_map.bak.json");
    }

    public File getLaneMapFile(String laneRaw, String pkg) {
        return new File(getLanePkgDir(laneRaw, pkg), "nav_map.json");
    }

    public File getLaneBackupMapFile(String laneRaw, String pkg) {
        return new File(getLanePkgDir(laneRaw, pkg), "nav_map.bak.json");
    }

    public File getMapFileForSource(String pkg, String sourceRaw) {
        String source = normalizeSource(sourceRaw);
        if ("stable".equals(source) || "candidate".equals(source) || "burn".equals(source)) {
            return getLaneMapFile(source, pkg);
        }
        return getLaneMapFile("stable", pkg);
    }

    public void setCurrentMap(String pkg, String mapJson) throws Exception {
        File pkgDir = getPkgDir(pkg);
        //noinspection ResultOfMethodCallIgnored
        pkgDir.mkdirs();

        File current = getCurrentMapFile(pkg);
        File backup = getBackupMapFile(pkg);

        if (current.exists()) {
            // Best-effort rotate to backup.
            //noinspection ResultOfMethodCallIgnored
            current.renameTo(backup);
        }

        writeUtf8(current, mapJson);
    }

    public void setLaneMap(String laneRaw, String pkg, String mapJson) throws Exception {
        File pkgDir = getLanePkgDir(laneRaw, pkg);
        //noinspection ResultOfMethodCallIgnored
        pkgDir.mkdirs();

        File current = getLaneMapFile(laneRaw, pkg);
        File backup = getLaneBackupMapFile(laneRaw, pkg);

        if (current.exists()) {
            //noinspection ResultOfMethodCallIgnored
            current.renameTo(backup);
        }

        writeUtf8(current, mapJson);
    }

    private File getPkgDir(String pkg) {
        // avoid path traversal; keep it simple.
        String safe = pkg.replace("/", "_").replace("\\", "_").trim();
        return new File(baseDir, safe);
    }

    private File getLanePkgDir(String laneRaw, String pkg) {
        String lane = normalizeSource(laneRaw);
        String safePkg = pkg.replace("/", "_").replace("\\", "_").trim();
        return new File(new File(new File(baseDir, LANE_ROOT_DIR), lane), safePkg);
    }

    private static String normalizeSource(String sourceRaw) {
        if (sourceRaw == null) {
            return "stable";
        }
        String source = sourceRaw.trim().toLowerCase();
        if ("candidates".equals(source)) {
            return "candidate";
        }
        if ("stable".equals(source) || "candidate".equals(source) || "burn".equals(source)) {
            return source;
        }
        return "stable";
    }

    private static void writeUtf8(File f, String s) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        fos.write(bytes);
        fos.flush();
        fos.close();
    }

    private static File getDefaultBaseDir() {
        String override = System.getProperty("lxb.map.dir");
        if (override != null && !override.trim().isEmpty()) {
            return new File(override.trim());
        }
        return new File("/data/local/tmp/lxb/maps");
    }
}
