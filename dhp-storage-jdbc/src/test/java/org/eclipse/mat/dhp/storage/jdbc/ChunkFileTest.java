package org.eclipse.mat.dhp.storage.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkFileTest {
    private static final int[] SPARSE = {11, 15};

    @TempDir
    File dir;

    private static byte[] blob(Random r, int len) {
        byte[] b = new byte[len];
        r.nextBytes(b);
        return b;
    }

    @Test
    void readsBackWhileWritingAfterFinishAndAfterReopen() throws Exception {
        File f = new File(dir, "x.chunks");
        Random r = new Random(1);
        byte[][] dense = new byte[300][];
        byte[] huge = blob(r, 20_000_000); // larger than the 8 MB write buffer
        byte[] classList = blob(r, 100);
        try (ChunkFile cf = ChunkFile.create(f, SPARSE)) {
            for (int c = 0; c < dense.length; c += 3) { // every third chunk only: the others stay absent
                dense[c] = blob(r, 1 + r.nextInt(5000));
                cf.put(2, c, dense[c]);
            }
            cf.put(11, 123_456_789L, huge);
            cf.put(15, 42, classList);
            cf.put(2, 0, dense[0]); // overwriting keeps the latest record

            // reads are possible before the file is finished
            assertThat(cf.read(2, 3)).isEqualTo(dense[3]);
            assertThat(cf.read(2, 4)).isNull();
            assertThat(cf.read(11, 123_456_789L)).isEqualTo(huge);

            cf.finish();
            assertThat(cf.isReadOnly()).isTrue();
            check(cf, dense, huge, classList);
            assertThatThrownBy(() -> cf.put(2, 1, new byte[1])).isInstanceOf(java.io.IOException.class);
        }
        try (ChunkFile cf = ChunkFile.open(f, SPARSE)) {
            check(cf, dense, huge, classList);
            assertThat(cf.chunkCount(2)).isEqualTo(298);
            assertThat(cf.chunkCount(5)).isZero();
        }
    }

    private static void check(ChunkFile cf, byte[][] dense, byte[] huge, byte[] classList) throws Exception {
        for (int c = 0; c < dense.length; c++) {
            if (dense[c] == null) {
                assertThat(cf.read(2, c)).isNull();
            } else {
                assertThat(cf.read(2, c)).isEqualTo(dense[c]);
            }
        }
        assertThat(cf.read(2, 100_000)).isNull();
        assertThat(cf.read(11, 123_456_789L)).isEqualTo(huge);
        assertThat(cf.read(11, 5)).isNull();
        assertThat(cf.read(15, 42)).isEqualTo(classList);
        assertThat(cf.read(7, 0)).isNull();
    }

    @Test
    void anUnfinishedFileIsRejected() throws Exception {
        File f = new File(dir, "y.chunks");
        try (ChunkFile cf = ChunkFile.create(f, SPARSE)) {
            cf.put(2, 0, new byte[]{1, 2, 3});
        }
        assertThatThrownBy(() -> ChunkFile.open(f, SPARSE)).isInstanceOf(java.io.IOException.class);
    }
}
