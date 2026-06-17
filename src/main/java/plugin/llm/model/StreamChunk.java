package plugin.llm.model;

public class StreamChunk {

    private final String delta;
    private final boolean done;
    private final String errorMessage;

    private StreamChunk(String delta, boolean done, String errorMessage) {
        this.delta = delta;
        this.done = done;
        this.errorMessage = errorMessage;
    }

    public static StreamChunk text(String delta) {
        return new StreamChunk(delta, false, null);
    }

    public static StreamChunk done() {
        return new StreamChunk(null, true, null);
    }

    public static StreamChunk error(String message) {
        return new StreamChunk(null, false, message);
    }

    public String getDelta() {
        return delta;
    }

    public boolean isDone() {
        return done;
    }

    public boolean isError() {
        return errorMessage != null;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
