package com.fathzer.healthtray.sources;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;

import com.dropbox.core.DbxDownloader;
import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.fathzer.healthtray.sources.DropboxSupplier;
import com.dropbox.core.v2.files.DbxUserFilesRequests;

/** Tests for {@link DropboxSupplier} using Mockito to mock the Dropbox SDK.
 * <BR>Verifies the scenarios:
 * <ul>
 *   <li>File exists &rarr; returns the server modification time as an {@link Instant}</li>
 *   <li>Path is a folder, not a file &rarr; throws {@link IOException}</li>
 *   <li>Dropbox API call fails &rarr; throws {@link IOException} wrapping the {@link DbxException}</li>
 *   <li>File download succeeds &rarr; returns the file's content as an {@link InputStream}</li>
 * </ul>
 */
class DropboxSupplierTest {

	@Test
	void fileExists_returnsServerModifiedInstant() throws Exception {
		Instant expected = Instant.parse("2026-01-15T10:30:00Z");
		FileMetadata fileMetadata = mock(FileMetadata.class);
		when(fileMetadata.getServerModified()).thenReturn(Date.from(expected));

		DbxUserFilesRequests filesRequests = mock(DbxUserFilesRequests.class);
		when(filesRequests.getMetadata("/backup/latest.tar")).thenReturn(fileMetadata);

		DbxClientV2 client = mock(DbxClientV2.class);
		when(client.files()).thenReturn(filesRequests);

		DropboxSupplier supplier = new DropboxSupplier(client, "/backup/latest.tar");
		Instant result = supplier.get();

		assertEquals(expected, result);
		verify(filesRequests).getMetadata("/backup/latest.tar");
	}

	@Test
	void pathIsFolder_throwsIOException() throws Exception {
		FolderMetadata folderMetadata = mock(FolderMetadata.class);

		DbxUserFilesRequests filesRequests = mock(DbxUserFilesRequests.class);
		when(filesRequests.getMetadata("/backup")).thenReturn(folderMetadata);

		DbxClientV2 client = mock(DbxClientV2.class);
		when(client.files()).thenReturn(filesRequests);

		DropboxSupplier supplier = new DropboxSupplier(client, "/backup");
		IOException e = assertThrows(IOException.class, supplier::get);
		assertTrue(e.getMessage().contains("Not a file"), "Expected message to contain 'Not a file', got: " + e.getMessage());
	}

	@Test
	void dropboxApiFails_throwsIOException() throws Exception {
		DbxUserFilesRequests filesRequests = mock(DbxUserFilesRequests.class);
		when(filesRequests.getMetadata(anyString())).thenThrow(new DbxException("Network error"));

		DbxClientV2 client = mock(DbxClientV2.class);
		when(client.files()).thenReturn(filesRequests);

		DropboxSupplier supplier = new DropboxSupplier(client, "/backup/latest.tar");
		IOException e = assertThrows(IOException.class, supplier::get);
		assertTrue(e.getMessage().contains("Dropbox error"), "Expected message to contain 'Dropbox error', got: " + e.getMessage());
		assertInstanceOf(DbxException.class, e.getCause());
	}

	@SuppressWarnings("unchecked")
	@Test
	void download_returnsInputStream() throws Exception {
		byte[] content = "backup content".getBytes();
		DbxDownloader<FileMetadata> downloader = mock(DbxDownloader.class);
		when(downloader.getInputStream()).thenReturn(new ByteArrayInputStream(content));

		DbxUserFilesRequests filesRequests = mock(DbxUserFilesRequests.class);
		when(filesRequests.download("/backup/latest.tar")).thenReturn(downloader);

		DbxClientV2 client = mock(DbxClientV2.class);
		when(client.files()).thenReturn(filesRequests);

		DropboxSupplier supplier = new DropboxSupplier(client, "/backup/latest.tar");
		try (InputStream in = supplier.open()) {
			assertArrayEquals(content, in.readAllBytes());
		}
		verify(filesRequests).download("/backup/latest.tar");
	}

	@Test
	void toString_returnsDropboxPath() {
		DbxClientV2 client = mock(DbxClientV2.class);
		DropboxSupplier supplier = new DropboxSupplier(client, "/backup/latest.tar");
		assertEquals("Dropbox:///backup/latest.tar", supplier.toString());
	}
}
