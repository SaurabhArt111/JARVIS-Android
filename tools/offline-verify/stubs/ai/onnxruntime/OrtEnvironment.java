package ai.onnxruntime;

/** STUB. */
public class OrtEnvironment implements AutoCloseable {
    public static OrtEnvironment getEnvironment() { throw new UnsupportedOperationException("stub"); }
    public OrtSession createSession(String modelPath, OrtSession.SessionOptions options) throws OrtException {
        throw new UnsupportedOperationException("stub");
    }
    @Override public void close() { throw new UnsupportedOperationException("stub"); }
}
