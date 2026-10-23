package com.cleanroommc.chunkflow.io;

public interface IRegionFile {

    boolean chunkflow$isClosed();

    void chunkflow$setClosed(boolean closed);

    long chunkflow$getLastAccess();

    void chunkflow$touch();

    /** @return true only if the file was idle (0 readers) and is now permanently closed. */
    boolean chunkflow$tryCloseLease();

    void chunkflow$forceCloseLease();

    int chunkflow$readers();
}
