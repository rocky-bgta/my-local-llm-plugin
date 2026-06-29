package plugin.memory;

import plugin.rag.RetrievalResult;

import java.util.*;

public class WorkingMemory {

    private String currentTask = "";
    private String targetSymbol = "";
    private String targetFile = "";
    private final List<String> recentlyModifiedFiles = new ArrayList<>();
    private final List<RetrievalResult> lastRetrievedContext = new ArrayList<>();
    private final List<String> validationErrors = new ArrayList<>();
    private boolean buildPassed = false;
    private boolean testsPassed = false;
    private int retryCount = 0;

    public void startTask(String task, String symbol, String file) {
        this.currentTask = task;
        this.targetSymbol = symbol != null ? symbol : "";
        this.targetFile = file != null ? file : "";
        this.recentlyModifiedFiles.clear();
        this.validationErrors.clear();
        this.buildPassed = false;
        this.testsPassed = false;
        this.retryCount = 0;
    }

    public void trackModifiedFile(String path) {
        if (!recentlyModifiedFiles.contains(path)) recentlyModifiedFiles.add(path);
    }

    public void setLastRetrievedContext(List<RetrievalResult> ctx) {
        lastRetrievedContext.clear();
        lastRetrievedContext.addAll(ctx);
    }

    public void addValidationError(String error) { validationErrors.add(error); }

    public void incrementRetry() { retryCount++; }

    public String getCurrentTask() { return currentTask; }
    public String getTargetSymbol() { return targetSymbol; }
    public String getTargetFile() { return targetFile; }
    public List<String> getRecentlyModifiedFiles() { return Collections.unmodifiableList(recentlyModifiedFiles); }
    public List<RetrievalResult> getLastRetrievedContext() { return Collections.unmodifiableList(lastRetrievedContext); }
    public List<String> getValidationErrors() { return Collections.unmodifiableList(validationErrors); }
    public boolean isBuildPassed() { return buildPassed; }
    public void setBuildPassed(boolean v) { this.buildPassed = v; }
    public boolean isTestsPassed() { return testsPassed; }
    public void setTestsPassed(boolean v) { this.testsPassed = v; }
    public int getRetryCount() { return retryCount; }
    public boolean canRetry(int maxRetries) { return retryCount < maxRetries; }
}
