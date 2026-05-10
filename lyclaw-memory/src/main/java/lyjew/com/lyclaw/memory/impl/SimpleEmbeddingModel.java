package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.vector.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class SimpleEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 768;

    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            log.debug("Embedding empty text, returning zero vector");
            return new float[DIMENSION];
        }

        float[] vector = new float[DIMENSION];

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));

            for (int i = 0; i < DIMENSION; i++) {
                int byteIndex = i % digest.length;
                int seed = ((digest[byteIndex] & 0xFF) << 8)
                         | (digest[(i * 7 + 3) % digest.length] & 0xFF);
                int phase = (digest[(i * 13 + 11) % digest.length] & 0xFF);
                double val = Math.sin(seed * 0.01 + phase * 0.1 + i * 0.001);
                vector[i] = (float) val;
            }

            l2Normalize(vector);

        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available, falling back to zero vector", e);
        }

        log.debug("Generated embedding of dimension {} for text ({} chars)", DIMENSION, text.length());
        return vector;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) result.add(embed(text));
        log.debug("Batch embedded {} texts", texts.size());
        return result;
    }

    @Override
    public int getDimension() { return DIMENSION; }

    @Override
    public String getModelName() { return "local-hash-v1"; }

    private void l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-12) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }
}
