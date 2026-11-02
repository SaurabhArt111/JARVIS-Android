package ai.onnxruntime;

import java.nio.FloatBuffer;

/** STUB. */
public class OnnxTensor implements OnnxValue {
    public static OnnxTensor createTensor(OrtEnvironment env, FloatBuffer data, long[] shape) throws OrtException {
        throw new UnsupportedOperationException("stub");
    }
    public FloatBuffer getFloatBuffer() { throw new UnsupportedOperationException("stub"); }
    @Override public void close() { throw new UnsupportedOperationException("stub"); }
}
