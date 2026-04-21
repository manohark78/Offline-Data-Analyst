package com.enterprise.dataanalyst.service.llm;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts text into 384-dimensional semantic vector using MiniLM transformer.
 *
 * This is the actual AI component.
 * "average salary by department" and "mean pay grouped by cost center"
 * produce mathematically similar vectors — zero keyword overlap needed.
 *
 * Used only for INTENT CLASSIFICATION.
 * Never receives actual data rows — schema only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final OrtSession ortSession;
    private final OrtEnvironment ortEnvironment;
    private final HuggingFaceTokenizer tokenizer;

    private static final int MAX_SEQ_LENGTH = 128;

    public float[] embed(String text) throws OrtException {
        // Tokenize
        Encoding encoding = tokenizer.encode(text, String.valueOf(true));
        long[] inputIds      = truncate(encoding.getIds(), MAX_SEQ_LENGTH);
        long[] attentionMask = truncate(encoding.getAttentionMask(), MAX_SEQ_LENGTH);
        long[] tokenTypeIds  = new long[inputIds.length];
        long[] shape = {1L, inputIds.length};

        // Build tensors
        OnnxTensor inputIdsTensor    = OnnxTensor.createTensor(ortEnvironment,
                LongBuffer.wrap(inputIds), shape);
        OnnxTensor attMaskTensor     = OnnxTensor.createTensor(ortEnvironment,
                LongBuffer.wrap(attentionMask), shape);
        OnnxTensor tokenTypeTensor   = OnnxTensor.createTensor(ortEnvironment,
                LongBuffer.wrap(tokenTypeIds), shape);

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids",      inputIdsTensor);
        inputs.put("attention_mask", attMaskTensor);
        inputs.put("token_type_ids", tokenTypeTensor);

        // Run model
        try (OrtSession.Result result = ortSession.run(inputs)) {
            float[][][] hiddenStates = (float[][][]) result.get(0).getValue();
            float[] pooled = meanPool(hiddenStates[0], attentionMask, inputIds.length);
            return l2Normalize(pooled);
        }
    }

    public float cosineSimilarity(float[] a, float[] b) {
        float dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private float[] meanPool(float[][] tokenVectors, long[] mask, int seqLen) {
        int dims = tokenVectors[0].length;
        float[] pooled = new float[dims];
        float maskSum = 0;
        for (int i = 0; i < seqLen; i++) {
            float m = mask[i];
            maskSum += m;
            for (int d = 0; d < dims; d++) {
                pooled[d] += tokenVectors[i][d] * m;
            }
        }
        if (maskSum > 0) {
            for (int d = 0; d < dims; d++) {
                pooled[d] /= maskSum;
            }
        }
        return pooled;
    }

    private float[] l2Normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / (norm + 1e-9));
        }
        return result;
    }

    private long[] truncate(long[] arr, int maxLen) {
        if (arr.length <= maxLen) return arr;
        long[] t = new long[maxLen];
        System.arraycopy(arr, 0, t, 0, maxLen);
        return t;
    }
}