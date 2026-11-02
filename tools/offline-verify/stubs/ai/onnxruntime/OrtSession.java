package ai.onnxruntime;

import java.util.Map;
import java.util.Set;

/** STUB. */
public class OrtSession implements AutoCloseable {
    public Set<String> getInputNames() { throw new UnsupportedOperationException("stub"); }

    public Result run(Map<String, OnnxTensor> inputs) throws OrtException { throw new UnsupportedOperationException("stub"); }

    @Override public void close() throws OrtException { throw new UnsupportedOperationException("stub"); }

    public static class SessionOptions implements AutoCloseable {
        public SessionOptions() { }
        public void setIntraOpNumThreads(int numThreads) throws OrtException { throw new UnsupportedOperationException("stub"); }
        public void setInterOpNumThreads(int numThreads) throws OrtException { throw new UnsupportedOperationException("stub"); }
        @Override public void close() { throw new UnsupportedOperationException("stub"); }
    }

    public static class Result implements AutoCloseable {
        public OnnxValue get(int index) { throw new UnsupportedOperationException("stub"); }
        @Override public void close() { throw new UnsupportedOperationException("stub"); }
    }
}
