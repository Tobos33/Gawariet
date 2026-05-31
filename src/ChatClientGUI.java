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
    private JTextArea friendsArea; // Miejsce na listę znajomych

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    public static void main(String[] args) {
        // Uruchomienie GUI w bezpiecznym wątku Swinga
        SwingUtilities.invokeLater(() -> new ChatClientGUI().initGUI());
    }

    public void initGUI() {
        // 1. Tworzenie głównego okna
        frame = new JFrame("Gawariet-Gawariet Client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLayout(new BorderLayout());

        // 2. Obszar czatu (środek)
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        frame.add(chatScrollPane, BorderLayout.CENTER);

        // 3. Panel boczny - Lista znajomych (prawo)
        friendsArea = new JTextArea(10, 12);
        friendsArea.setEditable(false);
        friendsArea.setText("ZNAJOMI:\n(Użyj GET_FRIENDS)");
        JScrollPane friendsScrollPane = new JScrollPane(friendsArea);
        frame.add(friendsScrollPane, BorderLayout.EAST);

        // 4. Panel dolny - wpisywanie wiadomości i wysyłanie
        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Wyślij");
        
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        // 5. Akcja wysyłania wiadomości (po kliknięciu Enter lub przycisku)
        inputField.addActionListener(e -> sendMessage());
        sendButton.addActionListener(e -> sendMessage());

        // Wyświetlenie okna i próba połączenia z siecią
        frame.setVisible(true);
        connectToServer();
    }

    private void connectToServer() {
        appendChat("Łączenie z serwerem...");
        
        try {
            // Wczytywanie konfiguracji
            Properties config = new Properties();
            try (FileInputStream file = new FileInputStream("Config.properties")) {
                config.load(file);
            }
            String serverAddress = config.getProperty("SERVER_ADRESS");
            int serverPort = Integer.parseInt(config.getProperty("PORT"));

            // Połączenie sieciowe
            socket = new Socket(serverAddress, serverPort);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            appendChat("Połączono pomyślnie!\n");

            // Wątek odbierający wiadomości z serwera w tle
            Thread receiverThread = new Thread(() -> {
                try {
                    String serverMessage;
                    while ((serverMessage = in.readLine()) != null) {
                        String finalMessage = serverMessage;
                        
                        // ZŁOTA ZASADA: Aktualizacja GUI z wątku tła musi być przez invokeLater
                        SwingUtilities.invokeLater(() -> {
                            // Sprawdzamy, czy serwer przysłał listę znajomych
                            if (finalMessage.startsWith("FRIENDS_LIST:")) {
                                parseFriendsList(finalMessage);
                            } else {
                                appendChat(finalMessage);
                            }
                        });
                    }
                } catch (IOException e) {
                    SwingUtilities.invokeLater(() -> appendChat("Połączenie z serwerem zostało przerwane."));
                }
            });
            receiverThread.setDaemon(true); // Wątek zamknie się razem z aplikacją
            receiverThread.start();

        } catch (Exception e) {
            appendChat("Błąd połączenia: " + e.getMessage());
        }
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty() && out != null) {
            out.println(text); // Wysyłamy komendę bezpośrednio do serwera
            inputField.setText(""); // Czyszczenie pola tekstowego
            
            if (text.equalsIgnoreCase("LOGOUT")) {
                frame.dispose(); // Zamyka okno
                System.exit(0);
            }
        }
    }

    private void appendChat(String message) {
        chatArea.append(message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength()); // Auto-scroll na dół
    }

    private void parseFriendsList(String rawData) {
        // Czyścimy panel i parsujemy np. FRIENDS_LIST:ewa(ONLINE),jan(OFFLINE)
        friendsArea.setText("ZNAJOMI:\n");
        String cleanData = rawData.replace("FRIENDS_LIST:", "");
        if(cleanData.equalsIgnoreCase("brak znajomych")) {
            friendsArea.append("Brak znajomych");
            return;
        }
        String[] friends = cleanData.split(",");
        for (String friend : friends) {
            if(!friend.isEmpty()) {
                friendsArea.append("• " + friend + "\n");
            }
        }
    }
}