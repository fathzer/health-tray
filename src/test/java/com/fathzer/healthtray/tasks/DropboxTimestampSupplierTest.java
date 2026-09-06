package com.fathzer.healthtray.tasks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.DbxUserFilesRequests;

/** Tests for {@link DropboxTimestampSupplier} using Mockito to mock the Dropbox SDK.
 * <BR>Verifies the scenarios:
 * <ul>
 *   <li>File exists &rarr; returns the server modification time as an {@link Instant}</li>
 *   <li>Path is a folder, not a file &rarr; throws {@link IOException}</li>
 *   <li>Dropbox API call fails &rarr; throws {@link IOException} wrapping the {@link DbxException}</li>
 * </ul>
 */
class DropboxTimestampSupplierTest {

	@Test
	void fileExists_returnsServerModifiedInstant() throws Exception {
		Instant expected = Instant.parse("2026-01-15T10:30:00Z");
		FileMetadata fileMetadata = mock(FileMetadata.class);
		when(fileMetadata.getServerModified()).thenReturn(Date.from(expected));

		DbxUserFilesRequests filesRequests = mock(DbxUserFilesRequests.class);
		when(filesRequests.getMetadata("/backup/latest.tar")).thenReturn(fileMetadata);

		DbxClientV2 client = mock(DbxClientV2.class);
		when(client.files()).thenReturn(filesRequests);

		DropboxTimestampSupplier supplier = new DropboxTimestampSupplier(client, "/backup/latest.tar");
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

		DropboxTimestampSupplier supplier = new DropboxTimestampSupplier(client, "/backup");
		IOException e = assertThrows(IOException.class, supplier::get);
		assertTrue(e.getMessage().contains("Not a file"), "Expected message to contain 'Not a file', got: " + e.getMessage());
	}

	@Test
	void dropboxApiFails_throwsIOException() throws Exception {
		DbxUserFilesRequests filesRequests = mock(DbxUserFilesRequests.class);
		when(filesRequests.getMetadata(anyString())).thenThrow(new DbxException("Network error"));

		DbxClientV2 client = mock(DbxClientV2.class);
		when(client.files()).thenReturn(filesRequests);

		DropboxTimestampSupplier supplier = new DropboxTimestampSupplier(client, "/backup/latest.tar");
		IOException e = assertThrows(IOException.class, supplier::get);
		assertTrue(e.getMessage().contains("Dropbox error"), "Expected message to contain 'Dropbox error', got: " + e.getMessage());
		assertInstanceOf(DbxException.class, e.getCause());
	}

	@Test
	void toString_returnsDropboxPath() {
		DbxClientV2 client = mock(DbxClientV2.class);
		DropboxTimestampSupplier supplier = new DropboxTimestampSupplier(client, "/backup/latest.tar");
		assertEquals("Dropbox:///backup/latest.tar", supplier.toString());
	}
}
