import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.Properties;

public class ChatClientGUI {
    private JFrame frame;
    private JTextArea chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private JTextArea friendsArea;
    private JButton refreshButton; // Nowy przycisk do ręcznego odświeżania

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatClientGUI().initGUI());
    }

    public void initGUI() {
        frame = new JFrame("Gawariet-Gawariet Client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(650, 400);
        frame.setLayout(new BorderLayout());

        // Obszar czatu (środek)
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        frame.add(chatScrollPane, BorderLayout.CENTER);

        // --- MODYFIKACJA: Panel boczny (prawo) ---
        JPanel rightPanel = new JPanel(new BorderLayout());

        // Przycisk odświeżania na samej górze prawego panelu
        refreshButton = new JButton("^=v Odśwież listę");
        rightPanel.add(refreshButton, BorderLayout.NORTH);

        // Pole tekstowe ze znajomymi pod przyciskiem
        friendsArea = new JTextArea(10, 44);
        friendsArea.setEditable(false);
        friendsArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        friendsArea.setText("ZNAJOMI:\n(Zaloguj się, aby pobrać)");
        JScrollPane friendsScrollPane = new JScrollPane(friendsArea);
        rightPanel.add(friendsScrollPane, BorderLayout.CENTER);

        // Dodanie całego złożonego panelu na prawo
        frame.add(rightPanel, BorderLayout.EAST);
        // -----------------------------------------

        // Panel dolny - wpisywanie wiadomości i wysyłanie
        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Wyślij");

        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        // Akcje przycisków i pól tekstowych
        inputField.addActionListener(e -> sendMessage());
        sendButton.addActionListener(e -> sendMessage());

        // Akcja dla nowego przycisku odświeżania
        refreshButton.addActionListener(e -> requestFriendsList());

        frame.setVisible(true);
        connectToServer();
    }

    private void connectToServer() {
        appendChat("Łączenie z serwerem...");

        try {
            Properties config = new Properties();
            try (FileInputStream file = new FileInputStream("Config.properties")) {
                config.load(file);
            }
            String serverAddress = config.getProperty("SERVER_ADRESS");
            int serverPort = Integer.parseInt(config.getProperty("PORT"));

            socket = new Socket(serverAddress, serverPort);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            appendChat("Połączono pomyślnie!\n");

            // Wątek odbierający wiadomości z serwera
            Thread receiverThread = new Thread(() -> {
                try {
                    String serverMessage;
                    boolean receivingFriendsBlock = false;
                    StringBuilder friendsBuilder = new StringBuilder();

                    while ((serverMessage = in.readLine()) != null) {
                        String finalMessage = serverMessage;

                        if (finalMessage.equals("=== TWOI ZNAJOMI ===")) {
                            receivingFriendsBlock = true;
                            friendsBuilder.setLength(0);
                            friendsBuilder.append("=== TWOI ZNAJOMI ===\n");
                            continue;
                        }

                        if (finalMessage.equals("====================")) {
                            receivingFriendsBlock = false;
                            friendsBuilder.append("====================");

                            String finalFriendsText = friendsBuilder.toString();
                            SwingUtilities.invokeLater(() -> friendsArea.setText(finalFriendsText));
                            continue;
                        }

                        if (receivingFriendsBlock) {
                            friendsBuilder.append(finalMessage).append("\n");
                        } else {
                            SwingUtilities.invokeLater(() -> {
                                appendChat(finalMessage);

                                // Automatyczne odświeżanie w kluczowych momentach akcji
                                if (finalMessage.startsWith("SUKCES: Zalogowano jako") ||
                                        finalMessage.startsWith("POWIADOMIENIE:") ||
                                        finalMessage.startsWith("SYSTEM: Zaakceptowano") ||
                                        finalMessage.startsWith("SYSTEM: Zaproszenie wysłane")) {

                                    requestFriendsList();
                                }
                            });
                        }
                    }
                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> appendChat("Połączenie z serwerem zostało przerwane."));
                }
            });
            receiverThread.setDaemon(true);
            receiverThread.start();

        } catch (Exception e) {
            appendChat("Błąd połączenia: " + e.getMessage());
        }
    }

    /**
     * Pomocnicza metoda wysyłająca prośbę o odświeżenie listy do serwera
     */
    private void requestFriendsList() {
        if (out != null) {
            out.println("FRIENDS");
        }
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty() && out != null) {
            out.println(text);
            inputField.setText("");

            if (text.equalsIgnoreCase("LOGOUT")) {
                frame.dispose();
                System.exit(0);
            }
        }
    }

    private void appendChat(String message) {
        chatArea.append(message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }
}