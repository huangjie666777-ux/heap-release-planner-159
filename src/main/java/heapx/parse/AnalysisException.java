package heapx.parse;

/** Rejects an upload: corrupt dump or limit exceeded. Nothing is published. */
public final class AnalysisException extends Exception {
    public final int status;
    public AnalysisException(int status, String message) {
        super(message);
        this.status = status;
    }
}
