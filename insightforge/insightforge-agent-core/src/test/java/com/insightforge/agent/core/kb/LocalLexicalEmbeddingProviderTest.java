package com.insightforge.agent.core.kb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLexicalEmbeddingProviderTest {

    @Test
    void deterministicNormalizedFixedDim() {
        LocalLexicalEmbeddingProvider p = new LocalLexicalEmbeddingProvider(512);
        float[] a = p.embed("秒杀库存不足需要补货");
        float[] b = p.embed("秒杀库存不足需要补货");
        assertEquals(512, a.length);
        assertArrayEquals(a, b, 1e-6f);
        double sum = 0;
        for (float x : a) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        assertEquals(1.0, norm, 1e-6);
    }

    @Test
    void relatedTextOutranksUnrelated() {
        LocalLexicalEmbeddingProvider p = new LocalLexicalEmbeddingProvider(512);
        String query = "商品下架后如何恢复上架";
        float[] qv = p.embed(query);
        double related = Vectors.cosine(qv, p.embed("下架的商品如何重新上架"));
        double unrelated = Vectors.cosine(qv, p.embed("今天天气很好适合户外跑步"));
        assertTrue(related > 0.3, "相关文本余弦应显著，实际=" + related);
        assertTrue(related > unrelated, "相关文本应高于无关文本");
    }
}
