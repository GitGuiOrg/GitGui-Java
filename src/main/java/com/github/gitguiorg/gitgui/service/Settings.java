package com.github.gitguiorg.gitgui.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {
    /** Explicit path to the git executable; null/blank = search the PATH. */
    public String gitPath;
    /** Explicit path to the GitButler CLI; null/blank = search PATH and the common install folders. */
    public String butPath;
    /** Use GitButler automatically in repositories that already use it (have .git/gitbutler), when but is installed. */
    public boolean gitButlerAuto = true;
    /** Per-repository override of the automatic choice, keyed by the normalized repository root. */
    public java.util.Map<String, Boolean> gitButlerRepos = new java.util.HashMap<>();
    public List<String> recentRepos = new ArrayList<>();
    public int maxRecent = 20;
    public int diffContextLines = 3;
    /** Diff view options, shared by every tab and persisted between runs. */
    public boolean diffSideBySide;
    public boolean diffIgnoreWhitespace;
    public boolean diffSyntax = true;
    public boolean diffIgnoreCrAtEol;
    public boolean diffIgnoreBlankLines;
    /** Beyond this many diff rows the rest is truncated with a notice. */
    public int diffMaxLines = 20_000;
    public int logPageSize = 500;
    /** Last folder used as the destination of a clone. */
    public String cloneDir;
    /** MERGE, REBASE or FF_ONLY. */
    public String pullMode = "FF_ONLY";
    /** Background fetch interval; 0 disables it. */
    public int autoFetchMinutes = 5;
    /** Commit dates: ABSOLUTE (yyyy-MM-dd HH:mm) or RELATIVE (3 h ago). */
    public String dateStyle = "ABSOLUTE";
    /** Commit dates in the local time zone or in UTC. */
    public String dateTimeZone = "LOCAL";

    // ---- window and session
    public double windowWidth = 1280;
    public double windowHeight = 800;
    public double windowX = Double.NaN;
    public double windowY = Double.NaN;
    public boolean windowMaximized;
    /** Divider positions of the last used tab: sidebar | content, content tabs | details, details | diff. */
    public double sidebarDivider = 0.15;
    public double mainDivider = 0.55;
    /** Divider between the content tabs and the changed-files panel, then between that panel and the details. */
    public double changesDivider = 0.78;
    public double detailsDivider = 0.3;
    /** Reopen the tabs of the previous run at startup. */
    public boolean restoreSession = true;
    public List<String> sessionRepos = new ArrayList<>();
    public String sessionActive;
}
