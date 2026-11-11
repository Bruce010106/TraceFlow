package dev.traceflow;

/** Safe diagnostic code only: never expose provider bodies or credentials. */
public final class ExtractionFailure extends RuntimeException {
    private final String code;
    public ExtractionFailure(String code) { super(code); this.code=code; }
    public String code() { return code; }
}
