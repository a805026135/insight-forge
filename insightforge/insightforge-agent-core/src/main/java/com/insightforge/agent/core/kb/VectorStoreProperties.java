package com.insightforge.agent.core.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库向量库配置（Phase 19）。
 * type=simple 为进程内 SimpleVectorStore（本地/测试零依赖）；
 * type=milvus 为生产 Milvus（etcd + minio + milvus standalone）。
 */
@ConfigurationProperties(prefix = "insightforge.agent.vectorstore")
public class VectorStoreProperties {

    /** simple（内存，默认）| milvus（生产） */
    private String type = "simple";

    private String milvusHost = "localhost";

    private int milvusPort = 19530;

    private String milvusDatabase = "default";

    private String milvusCollection = "insightforge_kb";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMilvusHost() {
        return milvusHost;
    }

    public void setMilvusHost(String milvusHost) {
        this.milvusHost = milvusHost;
    }

    public int getMilvusPort() {
        return milvusPort;
    }

    public void setMilvusPort(int milvusPort) {
        this.milvusPort = milvusPort;
    }

    public String getMilvusDatabase() {
        return milvusDatabase;
    }

    public void setMilvusDatabase(String milvusDatabase) {
        this.milvusDatabase = milvusDatabase;
    }

    public String getMilvusCollection() {
        return milvusCollection;
    }

    public void setMilvusCollection(String milvusCollection) {
        this.milvusCollection = milvusCollection;
    }
}
