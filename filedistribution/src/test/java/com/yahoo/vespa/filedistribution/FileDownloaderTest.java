//  Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.vespa.filedistribution;

import com.yahoo.config.FileReference;
import com.yahoo.io.IOUtils;
import com.yahoo.jrt.Int32Value;
import com.yahoo.jrt.Request;
import com.yahoo.jrt.RequestWaiter;
import com.yahoo.jrt.StringValue;
import com.yahoo.jrt.Supervisor;
import com.yahoo.jrt.Transport;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.config.Connection;
import com.yahoo.vespa.config.ConnectionPool;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileDownloaderTest {

    private final XXHash64 hasher = XXHashFactory.fastestInstance().hash64();

    private MockConnection connection;
    private FileDownloader fileDownloader;
    private File downloadDir;

    @Before
    public void setup() {
        try {
            downloadDir = Files.createTempDirectory("filedistribution").toFile();
            connection = new MockConnection();
            fileDownloader = new FileDownloader(connection, downloadDir, Duration.ofMillis(3000));
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void getFile() throws IOException {
        File downloadDir = fileDownloader.downloadDirectory();

        {
            // fileReference already exists on disk, does not have to be downloaded

            String fileReferenceString = "foo";
            String filename = "foo.jar";
            FileReference fileReference = new FileReference(fileReferenceString);
            File fileReferenceFullPath = fileReferenceFullPath(downloadDir, fileReference);
            writeFileReference(downloadDir, fileReferenceString, filename);

            // Check that we get correct path and content when asking for file reference
            Optional<File> pathToFile = fileDownloader.getFile(fileReference);
            assertTrue(pathToFile.isPresent());
            String downloadedFile = new File(fileReferenceFullPath, filename).getAbsolutePath();
            assertEquals(new File(fileReferenceFullPath, filename).getAbsolutePath(), downloadedFile);
            assertEquals("content", IOUtils.readFile(pathToFile.get()));

            // Verify download status when downloaded
            assertDownloadStatus(fileDownloader, fileReference, 100.0);
        }

        {
            // fileReference does not exist on disk, needs to be downloaded, but fails when asking upstream for file)

            connection.setResponseHandler(new MockConnection.UnknownFileReferenceResponseHandler());

            FileReference fileReference = new FileReference("bar");
            File fileReferenceFullPath = fileReferenceFullPath(downloadDir, fileReference);
            assertFalse(fileReferenceFullPath.getAbsolutePath(), fileDownloader.getFile(fileReference).isPresent());

            // Verify download status when unable to download
            assertDownloadStatus(fileDownloader, fileReference, 0.0);
        }

        {
            // fileReference does not exist on disk, needs to be downloaded)

            FileReference fileReference = new FileReference("fileReference");
            File fileReferenceFullPath = fileReferenceFullPath(downloadDir, fileReference);
            assertFalse(fileReferenceFullPath.getAbsolutePath(), fileDownloader.getFile(fileReference).isPresent());

            // Verify download status
            assertDownloadStatus(fileDownloader, fileReference, 0.0);

            // Receives fileReference, should return and make it available to caller
            String filename = "abc.jar";
            receiveFile(fileReference, filename, "some other content");
            Optional<File> downloadedFile = fileDownloader.getFile(fileReference);

            assertTrue(downloadedFile.isPresent());
            File downloadedFileFullPath = new File(fileReferenceFullPath, filename);
            assertEquals(downloadedFileFullPath.getAbsolutePath(), downloadedFile.get().getAbsolutePath());
            assertEquals("some other content", IOUtils.readFile(downloadedFile.get()));

            // Verify download status when downloaded
            assertDownloadStatus(fileDownloader, fileReference, 100.0);
        }
    }

    @Test
    public void setFilesToDownload() throws IOException {
        Duration timeout = Duration.ofMillis(200);
        File downloadDir = Files.createTempDirectory("filedistribution").toFile();
        MockConnection connectionPool = new MockConnection();
        connectionPool.setResponseHandler(new MockConnection.WaitResponseHandler(timeout.plus(Duration.ofMillis(1000))));
        FileDownloader fileDownloader = new FileDownloader(connectionPool, downloadDir, timeout);
        FileReference foo = new FileReference("foo");
        FileReference bar = new FileReference("bar");
        List<FileReference> fileReferences = Arrays.asList(foo, bar);
        fileDownloader.queueForDownload(fileReferences);

        // Verify download status
        assertDownloadStatus(fileDownloader, foo, 0.0);
        assertDownloadStatus(fileDownloader, bar, 0.0);
    }

    @Test
    public void receiveFile() throws IOException {
        FileReference foo = new FileReference("foo");
        String filename = "foo.jar";
        receiveFile(foo, filename, "content");
        File downloadedFile = new File(fileReferenceFullPath(downloadDir, foo), filename);
        assertEquals("content", IOUtils.readFile(downloadedFile));
    }

    private void writeFileReference(File dir, String fileReferenceString, String fileName) throws IOException {
        File file = new File(new File(dir, fileReferenceString), fileName);
        IOUtils.writeFile(file, "content", false);
    }

    private File fileReferenceFullPath(File dir, FileReference fileReference) {
        return new File(dir, fileReference.value());
    }

    private void assertDownloadStatus(FileDownloader fileDownloader, FileReference fileReference, double expectedDownloadStatus) {
        double downloadStatus = fileDownloader.downloadStatus(fileReference);
        assertEquals(expectedDownloadStatus, downloadStatus, 0.0001);
    }

    private void receiveFile(FileReference fileReference, String filename, String content) {
        byte[] contentBytes = Utf8.toBytes(content);
        long xxHashFromContent = hasher.hash(ByteBuffer.wrap(contentBytes), 0);
        fileDownloader.receiveFile(fileReference, filename, contentBytes, xxHashFromContent);
    }

    private static class MockConnection implements ConnectionPool, com.yahoo.vespa.config.Connection {

        private ResponseHandler responseHandler;

        MockConnection() {
            this(new FileReferenceFoundResponseHandler());
        }

        MockConnection(ResponseHandler responseHandler) {
            this.responseHandler = responseHandler;
        }

        @Override
        public void invokeAsync(Request request, double jrtTimeout, RequestWaiter requestWaiter) {
            responseHandler.request(request);
        }

        @Override
        public void invokeSync(Request request, double jrtTimeout) {
            responseHandler.request(request);
        }

        @Override
        public void setError(int errorCode) {
        }

        @Override
        public void setSuccess() {
        }

        @Override
        public String getAddress() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public void setError(Connection connection, int errorCode) {
            connection.setError(errorCode);
        }

        @Override
        public Connection getCurrent() {
            return this;
        }

        @Override
        public Connection setNewCurrentConnection() {
            return this;
        }

        @Override
        public int getSize() {
            return 1;
        }

        @Override
        public Supervisor getSupervisor() {
            return new Supervisor(new Transport());
        }

        void setResponseHandler(ResponseHandler responseHandler) {
            this.responseHandler = responseHandler;
        }

        public interface ResponseHandler {
            void request(Request request);
        }

        static class FileReferenceFoundResponseHandler implements MockConnection.ResponseHandler {

            @Override
            public void request(Request request) {
                if (request.methodName().equals("filedistribution.serveFile")) {
                    request.returnValues().add(new Int32Value(0));
                    request.returnValues().add(new StringValue("OK"));
                }
            }
        }

        static class UnknownFileReferenceResponseHandler implements MockConnection.ResponseHandler {

            @Override
            public void request(Request request) {
                if (request.methodName().equals("filedistribution.serveFile")) {
                    request.returnValues().add(new Int32Value(1));
                    request.returnValues().add(new StringValue("Internal error"));
                }
            }
        }

        static class WaitResponseHandler implements MockConnection.ResponseHandler {

            private final Duration waitUntilAnswering;

            WaitResponseHandler(Duration waitUntilAnswering) {
                super();
                this.waitUntilAnswering = waitUntilAnswering;
            }

            @Override
            public void request(Request request) {
                try { Thread.sleep(waitUntilAnswering.toMillis());} catch (InterruptedException e) { /* do nothing */ }

                if (request.methodName().equals("filedistribution.serveFile")) {
                    request.returnValues().add(new Int32Value(0));
                    request.returnValues().add(new StringValue("OK"));
                }

            }
        }
    }

}