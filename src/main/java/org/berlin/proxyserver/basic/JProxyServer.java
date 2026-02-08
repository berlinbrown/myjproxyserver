package org.berlin.proxyserver.basic;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class JProxyServer {

    // ================= CONFIG =================
    private static volatile int proxyPort = 9088;

    // ================= GUI ====================
    private static JTextArea requestArea;
    private static JTextArea responseArea;
    private static JLabel statusLabel;

    private static final AtomicLong bytesTransferred = new AtomicLong();

    // ================= THREADING ==============
    private static final ExecutorService threadPool =
            Executors.newFixedThreadPool(32);

    // ================= MAIN ===================
    public static void main(String[] args) {
        parseArgs(args);

        SwingUtilities.invokeLater(JProxyServer::startGui);
        new Thread(new ProxyServer(), "ProxyServer").start();
    }

    private static void parseArgs(String[] args) {
        for (String a : args) {
            if (a.startsWith("--port=")) {
                proxyPort = Integer.parseInt(a.substring(7));
            }
        }
    }

    // ================= GUI ====================
    private static void startGui() {
        JFrame frame = new JFrame("MyProxyServer");
        frame.setSize(900, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        requestArea = new JTextArea();
        responseArea = new JTextArea();
        requestArea.setEditable(false);
        responseArea.setEditable(false);

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(requestArea),
                new JScrollPane(responseArea)
        );
        split.setDividerLocation(300);

        statusLabel = new JLabel("Ready");

        frame.setJMenuBar(createMenu(frame));
        frame.add(split, BorderLayout.CENTER);
        frame.add(statusLabel, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    private static JMenuBar createMenu(JFrame frame) {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        file.add(exit);

        JMenu settings = new JMenu("Settings");
        JMenuItem port = new JMenuItem("Set Port");
        port.addActionListener(e -> changePort(frame));
        settings.add(port);

        bar.add(file);
        bar.add(settings);
        return bar;
    }

    private static void changePort(JFrame frame) {
        String input = JOptionPane.showInputDialog(
                frame, "Proxy Port:", proxyPort
        );
        if (input != null) {
            proxyPort = Integer.parseInt(input.trim());
            logRequest("Port updated to " + proxyPort);
        }
    }

    // ================= PROXY ==================
    static class ProxyServer implements Runnable {

        @Override
        public void run() {
            try (ServerSocket server = new ServerSocket(proxyPort)) {
                logRequest("Proxy listening on port " + proxyPort);

                while (true) {
                    Socket client = server.accept();
                    threadPool.submit(new ProxyHandler(client));
                }
            } catch (IOException e) {
                logRequest("Server error: " + e.getMessage());
            }
        }
    }

    static class ProxyHandler implements Runnable {

        private final Socket client;

        ProxyHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try (
                    InputStream clientIn = client.getInputStream();
                    OutputStream clientOut = client.getOutputStream();
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(clientIn))
            ) {
                String requestLine = reader.readLine();
                if (requestLine == null) return;

                List<String> headers = new ArrayList<>();
                String hostHeader = null;
                String line;

                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    headers.add(line);
                    if (line.toLowerCase().startsWith("host:")) {
                        hostHeader = line.substring(5).trim();
                    }
                }

                logRequest("==== REQUEST ====");
                logRequest(requestLine);
                headers.forEach(JProxyServer::logRequest);

                if (hostHeader == null) return;

                String host = hostHeader;
                int port = 80;

                if (host.contains(":")) {
                    String[] p = host.split(":");
                    host = p[0];
                    port = Integer.parseInt(p[1]);
                }

                String[] parts = requestLine.split(" ");
                URI uri = URI.create(parts[1]);

                String path =
                        (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                                ? "/"
                                : uri.getRawPath();

                if (uri.getRawQuery() != null) {
                    path += "?" + uri.getRawQuery();
                }

                String newRequestLine =
                        parts[0] + " " + path + " " + parts[2];

                try (
                        Socket server = new Socket(host, port);
                        InputStream serverIn = server.getInputStream();
                        OutputStream serverOut = server.getOutputStream()
                ) {
                    serverOut.write((newRequestLine + "\r\n").getBytes());
                    for (String h : headers) {
                        serverOut.write((h + "\r\n").getBytes());
                    }
                    serverOut.write("\r\n".getBytes());
                    serverOut.flush();

                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = serverIn.read(buffer)) != -1) {
                        clientOut.write(buffer, 0, read);
                        bytesTransferred.addAndGet(read);
                        logResponse(new String(buffer, 0, read));
                        updateStatus();
                    }
                }

            } catch (Exception e) {
                logRequest("Proxy error: " + e.getMessage());
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ================= LOGGING =================
    private static void logRequest(String s) {
        SwingUtilities.invokeLater(() -> {
            requestArea.append(s + "\n");
            requestArea.setCaretPosition(
                    requestArea.getDocument().getLength()
            );
        });
    }

    private static void logResponse(String s) {
        SwingUtilities.invokeLater(() -> {
            responseArea.append(s);
            responseArea.setCaretPosition(
                    responseArea.getDocument().getLength()
            );
        });
    }

    private static void updateStatus() {
        SwingUtilities.invokeLater(() ->
                statusLabel.setText(
                        "Bytes transferred: " + bytesTransferred.get()
                )
        );
    }
}