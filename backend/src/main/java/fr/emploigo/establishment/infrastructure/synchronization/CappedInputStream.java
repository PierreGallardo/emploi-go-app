package fr.emploigo.establishment.infrastructure.synchronization;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** A stream that fails once more than its limit has been read through it. */
final class CappedInputStream extends FilterInputStream {
    private final long limit;
    private long read;

    CappedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) count(1);
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count > 0) count(count);
        return count;
    }

    @Override
    public long skip(long length) throws IOException {
        long skipped = super.skip(length);
        count(skipped);
        return skipped;
    }

    private void count(long bytes) throws IOException {
        read += bytes;
        if (read > limit)
            throw new IOException("plus de " + limit + " octets une fois décompressé");
    }
}
