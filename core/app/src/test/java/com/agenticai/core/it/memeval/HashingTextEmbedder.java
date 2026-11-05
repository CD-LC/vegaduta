package com.agenticai.core.it.memeval;

import com.agenticai.core.knowledge.embedding.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic text-similarity embedder for the real-platform memory eval: no model, no network,
 * 384 dimensions (the width of the platform's memory columns). It replaces the embedding sidecar,
 * so retrieval quality in the eval is the quality of THIS embedder plus the real SQL, top-k and
 * trust filters; it is not a claim about the production model.
 *
 * <p>Features (signed feature hashing into 384 buckets, L2-normalised): stemmed content words,
 * adjacent word pairs at 0.6 weight and word-internal character trigrams at 0.25 weight (so
 * "invoice" and "invoices", or a one-letter typo, stay close), with a small English and
 * Hindi/Hinglish stop list removed. Hindi (Devanagari) and Hinglish tokens are ordinary words to
 * it.
 *
 * <p><b>Limits, on purpose:</b> it is lexical. Synonyms ("car" / "vehicle"), paraphrase, negation,
 * word order beyond adjacent pairs, transliteration (Hinglish vs Devanagari) and cross-language
 * matches are invisible to it, and 384 buckets collide: two unrelated texts can share a bucket and
 * score a small positive similarity (the noise floor is about 0.05 for a dozen features). A real
 * embedder is better at paraphrase and worse at exact tokens such as ids and numbers, which this
 * one matches perfectly; scores here are therefore optimistic for identifier-style probes and
 * pessimistic for paraphrased ones.
 */
public final class HashingTextEmbedder implements EmbeddingClient {

    public static final int DIMENSIONS = 384;

    private static final double PAIR_WEIGHT = 0.6;
    private static final double TRIGRAM_WEIGHT = 0.25;

    private static final Set<String> STOP =
            Set.of(
                    "a", "an", "the", "is", "are", "was", "were", "be", "been", "am", "of", "to",
                    "in", "on", "at", "for", "and", "or", "my", "me", "i", "you", "your", "it",
                    "its", "this", "that", "with", "by", "as", "do", "does", "did", "what", "which",
                    "who", "when", "where", "how", "please", "us", "we", "our", "has", "have",
                    "had", "now", "hai", "hain", "ka", "ki", "ke", "ko", "se", "mein", "mera",
                    "meri", "mere", "kya", "aur", "ye", "yeh", "है", "हैं", "का", "की", "के", "को",
                    "से", "में", "मेरा", "मेरी", "मेरे", "क्या", "और", "यह");

    @Override
    public String provider() {
        return "memeval-hashing";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }

    /**
     * The L2-normalised vector of {@code text}; text with no content word maps to one fixed unit
     * vector.
     */
    public static float[] embed(String text) {
        double[] acc = new double[DIMENSIONS];
        List<String> words = contentWords(text);
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            add(acc, "u:" + w, 1.0);
            if (i + 1 < words.size()) {
                add(acc, "b:" + w + "_" + words.get(i + 1), PAIR_WEIGHT);
            }
            if (w.length() >= 5) {
                String padded = "^" + w + "$";
                for (int j = 0; j + 3 <= padded.length(); j++) {
                    add(acc, "t:" + padded.substring(j, j + 3), TRIGRAM_WEIGHT);
                }
            }
        }
        double norm = 0;
        for (double v : acc) {
            norm += v * v;
        }
        float[] out = new float[DIMENSIONS];
        if (norm == 0) {
            out[DIMENSIONS - 1] = 1f;
            return out;
        }
        double inv = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            out[i] = (float) (acc[i] * inv);
        }
        return out;
    }

    /** Cosine similarity of two texts under this embedder, in [-1, 1]. */
    public static double similarity(String a, String b) {
        float[] x = embed(a);
        float[] y = embed(b);
        double dot = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            dot += (double) x[i] * y[i];
        }
        return dot;
    }

    /** Lower-cased, stemmed words without stop words, in order. */
    public static List<String> contentWords(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String norm = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String raw : norm.split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (raw.isEmpty() || STOP.contains(raw)) {
                continue;
            }
            out.add(stem(raw));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() <= 4 || !w.chars().allMatch(c -> c < 128)) {
            return w;
        }
        if (w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("es")
                && !(w.endsWith("ches")
                        || w.endsWith("shes")
                        || w.endsWith("sses")
                        || w.endsWith("xes")
                        || w.endsWith("zes"))) {
            return w.substring(0, w.length() - 1); // "invoices" -> "invoice"
        }
        for (String suffix : new String[] {"ing", "ed", "es", "s"}) {
            if (w.endsWith(suffix) && w.length() - suffix.length() >= 3) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    private static void add(double[] acc, String feature, double weight) {
        long h = 0xcbf29ce484222325L;
        for (byte b : feature.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        h ^= (h >>> 29);
        h *= 0xbf58476d1ce4e5b9L;
        h ^= (h >>> 32);
        int bucket = (int) Long.remainderUnsigned(h >>> 1, DIMENSIONS);
        acc[bucket] += (h & 1L) == 0 ? weight : -weight;
    }
}
