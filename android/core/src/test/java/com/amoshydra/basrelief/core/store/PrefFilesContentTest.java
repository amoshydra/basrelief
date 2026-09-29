package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The share path used to write every shared image to one fixed filename, so
 * nine gallery rows all read the same bytes and every earlier photo was gone.
 * These pin the check that stops it recurring, and pin the two ways that check
 * could be made too eager: collapsing two genuinely different photos, and
 * matching a file that is not there.
 */
public class PrefFilesContentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String name, byte[] bytes) throws Exception {
        File f = tmp.newFile(name);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return f;
    }

    private static byte[] bytes(int seed, int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) b[i] = (byte) ((i * 31 + seed) & 0xFF);
        return b;
    }

    @Test
    public void identicalBytesAreTheSameImage() throws Exception {
        byte[] a = bytes(1, 4096);
        assertTrue(PrefFiles.sameContent(a, write("one.png", a)));
    }

    @Test
    public void differentBytesOfTheSameLengthAreNotTheSameImage() throws Exception {
        // Two different photos can share a byte count exactly, so length alone
        // must not be enough to collapse them into one gallery row.
        assertFalse(PrefFiles.sameContent(bytes(1, 4096), write("two.png", bytes(2, 4096))));
    }

    @Test
    public void differentLengthsAreNotTheSameImage() throws Exception {
        assertFalse(PrefFiles.sameContent(bytes(1, 4096), write("three.png", bytes(1, 2048))));
    }

    @Test
    public void oneChangedByteIsEnoughToDiffer() throws Exception {
        byte[] a = bytes(1, 4096);
        byte[] b = a.clone();
        b[2047] ^= 0x01;
        assertFalse(PrefFiles.sameContent(a, write("four.png", b)));
    }

    @Test
    public void aMissingFileIsNeverAMatch() {
        // Otherwise an import could be silently swallowed because the row that
        // "already has it" has lost its file.
        assertFalse(PrefFiles.sameContent(bytes(1, 4096), new File(tmp.getRoot(), "absent.png")));
    }

    @Test
    public void aDirectoryIsNeverAMatch() throws Exception {
        assertFalse(PrefFiles.sameContent(bytes(1, 4096), tmp.newFolder("adir")));
    }

    @Test
    public void nullsAreNeverAMatch() throws Exception {
        assertFalse(PrefFiles.sameContent(null, write("five.png", bytes(1, 16))));
        assertFalse(PrefFiles.sameContent(bytes(1, 16), null));
    }

    @Test
    public void anEmptyFileMatchesOnlyAnEmptyArray() throws Exception {
        File empty = write("six.png", new byte[0]);
        assertTrue(PrefFiles.sameContent(new byte[0], empty));
        assertFalse(PrefFiles.sameContent(bytes(1, 1), empty));
    }
}
