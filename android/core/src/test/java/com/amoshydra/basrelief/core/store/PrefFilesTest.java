package com.amoshydra.basrelief.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * The restore path is the one place a mistake silently costs the user their
 * image, so the resolution rules are pinned here rather than left to prose.
 */
public class PrefFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String name) throws Exception {
        File f = tmp.newFile(name);
        return f;
    }

    @Test
    public void storesABareFilenameNotAPath() {
        assertEquals("photo.png", PrefFiles.store(new File("/data/user/0/pkg/files/photo.png")));
    }

    @Test
    public void resolvesACurrentRelativeValue() throws Exception {
        write("photo.png");
        File got = PrefFiles.resolve(tmp.getRoot(), "photo.png");
        assertTrue(got != null && got.exists());
        assertEquals("photo.png", got.getName());
    }

    /** The bug this class exists to prevent. */
    @Test
    public void resolvesAnAbsolutePathRestoredFromAnotherDevice() throws Exception {
        // prefs and filesDir are both backed up, so after a restore the file is
        // here but the stored value still says /data/user/0/.../files/photo.png
        write("photo.png");
        String staleAbsolute = "/data/user/0/com.amoshydra.basrelief/files/photo.png";
        File got = PrefFiles.resolve(tmp.getRoot(), staleAbsolute);
        assertTrue("a restored absolute value must re-resolve to the local file",
                got != null && got.exists());
        assertEquals("photo.png", got.getName());
    }

    /** An upgrade from a build that wrote absolute paths must not lose the image. */
    @Test
    public void keepsALegacyAbsolutePathThatStillExists() throws Exception {
        File f = write("legacy.png");
        File got = PrefFiles.resolve(tmp.getRoot(), f.getAbsolutePath());
        assertTrue(got != null);
        assertEquals(f.getAbsolutePath(), got.getAbsolutePath());
    }

    @Test
    public void returnsNullWhenTheFileIsGone() {
        assertNull("must not hand back a dangling path",
                PrefFiles.resolve(tmp.getRoot(), "missing.png"));
    }

    @Test
    public void returnsNullForEmptyValues() {
        assertNull(PrefFiles.resolve(tmp.getRoot(), null));
        assertNull(PrefFiles.resolve(tmp.getRoot(), ""));
    }

    /** Nothing under filesDir may be escaped by a hostile/odd stored value. */
    @Test
    public void cannotEscapeFilesDirViaTraversal() throws Exception {
        write("secret.png");
        File outside = new File(tmp.getRoot().getParentFile(), "outside.png");
        assertTrue(outside.createNewFile());
        try {
            File got = PrefFiles.resolve(tmp.getRoot(), "../outside.png");
            assertTrue("must not resolve outside filesDir", got == null
                    || got.getCanonicalPath().startsWith(
                            tmp.getRoot().getCanonicalPath() + File.separator));
        } finally {
            outside.delete();
        }
    }
}
