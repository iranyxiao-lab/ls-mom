package com.lsmom.ai.knowledge;

/** 文本向量化。不同模型的向量不可混用：分块记录模型名，检索只比较同一模型的向量。 */
public interface Embedder {

    String model();

    float[] embed(String text);
}
