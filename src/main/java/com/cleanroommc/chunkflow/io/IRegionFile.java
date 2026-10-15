package com.cleanroommc.chunkflow.io;

public interface IRegionFile {

    boolean chunkflow$isClosed();

    void chunkflow$setClosed(boolean closed);

    long chunkflow$getLastAccess();

    void chunkflow$touch();
}
