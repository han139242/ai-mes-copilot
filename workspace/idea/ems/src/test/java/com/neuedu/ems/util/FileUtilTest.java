package com.neuedu.ems.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilTest {

    @TempDir
    Path testRoot;

    private FileUtil fileUtil;

    @BeforeEach
    void setUp() {
        fileUtil = new FileUtil();
        ReflectionTestUtils.setField(fileUtil, "allowTypes", "jpg,png,jpeg,gif,pdf");
    }

    @Test
    void acceptsConfiguredExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "image.PNG", "image/png", new byte[]{1});

        assertDoesNotThrow(() -> fileUtil.checkAvatarFileType(file));
    }

    @Test
    void rejectsMissingOrPartialExtension() {
        MockMultipartFile noExtension = new MockMultipartFile("file", "image", "application/octet-stream", new byte[]{1});
        MockMultipartFile partialExtension = new MockMultipartFile("file", "image.p", "application/octet-stream", new byte[]{1});

        assertThrows(RuntimeException.class, () -> fileUtil.checkAvatarFileType(noExtension));
        assertThrows(RuntimeException.class, () -> fileUtil.checkAvatarFileType(partialExtension));
    }

    @Test
    void rejectsTempPathOutsideStorageWithoutDeletingSource() throws Exception {
        Path tempRoot = Files.createDirectory(testRoot.resolve("temp"));
        Path formalRoot = Files.createDirectory(testRoot.resolve("formal"));
        Path outsideFile = Files.writeString(testRoot.resolve("outside.txt"), "keep");
        ReflectionTestUtils.setField(fileUtil, "tempRootPath", tempRoot.toString());
        ReflectionTestUtils.setField(fileUtil, "rootPath", formalRoot.toString());

        assertThrows(RuntimeException.class, () -> fileUtil.moveTempToFormal("../outside.txt"));
        assertTrue(Files.exists(outsideFile));
    }

    @Test
    void deletesFileAndAllowsRepeatedDeletion() throws Exception {
        Path file = Files.writeString(testRoot.resolve("delete-me.txt"), "temporary");

        fileUtil.deleteFile(file.toString());

        assertTrue(Files.notExists(file));
        assertDoesNotThrow(() -> fileUtil.deleteFile(file.toString()));
    }

    @Test
    void reportsDeletionFailureInsteadOfSilentlySucceeding() throws Exception {
        Path folder = Files.createDirectory(testRoot.resolve("nonempty"));
        Files.writeString(folder.resolve("keep.txt"), "keep");

        assertThrows(IllegalStateException.class, () -> fileUtil.deleteFile(folder.toString()));
        assertTrue(Files.exists(folder.resolve("keep.txt")));
    }

    @Test
    void randomPathKeepsPortableShape() {
        ReflectionTestUtils.setField(fileUtil, "firstLevelLength", 2);
        ReflectionTestUtils.setField(fileUtil, "secondLevelLength", 3);

        assertTrue(fileUtil.generateTwoLevelRandomPath().matches("[A-Za-z0-9]{2}/[A-Za-z0-9]{3}/"));
    }
}
