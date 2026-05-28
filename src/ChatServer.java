import java.io.*;
import java.net.*;
import java.util.Properties;
import java.util.concurrent.*;

public class ChatServer {
    // private static final int PORT = 12345;

    // Prosta, wbudowana "baza danych" użytkowników (login -> hasło)
    private static final ConcurrentHashMap<String, String> userDatabase = new ConcurrentHashMap<>();

    // Przechowuje aktualnie zalogowanych użytkowników (login -> handler obsługujący połączenie)
    private static final ConcurrentHashMap<String, ClientHandler> activeUsers = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
    	
    	Properties config = new Properties();
        
        FileInputStream file = new FileInputStream("Config.properties");
        config.load(file);
        
        int PORT = Integer.valueOf(config.getProperty("PORT"));
        
        // Inicjalizacja przykładowych użytkowników
        userDatabase.put("adam", "haslo123");
        userDatabase.put("ewa", "tajne456");
        userDatabase.put("jan", "qwerty");

        System.out.println("Uruchamianie serwera na porcie " + PORT + "...");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Serwer nasłuchuje na połączenia.");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Nowe połączenie: " + clientSocket.getInetAddress());

                // Tworzenie i uruchamianie nowego wątku dla klienta
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Błąd serwera: " + e.getMessage());
        }
    }

    // Klasa wewnętrzna obsługująca pojedynczego klienta
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String loggedInUser = null;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                out.println("Witaj na serwerze! Zaloguj się używając komendy: LOGIN <twój_login> <twoje_hasło>");

                String message;
                while ((message = in.readLine()) != null) {
                    String[] tokens = message.split(" ", 3); // Dzieli wiadomość na max 3 części
                    String command = tokens[0].toUpperCase();

                    // Faza logowania
                    if (loggedInUser == null) {
                        if (command.equals("LOGIN") && tokens.length == 3) {
                            String login = tokens[1];
                            String password = tokens[2];
                            handleLogin(login, password);
                        } else {
                            out.println("BŁĄD: Musisz się najpierw zalogować. Użyj: LOGIN <login> <hasło>");
                        }
                    }
                    // Faza po zalogowaniu
                    else {
                        if (command.equals("SEND") && tokens.length == 3) {
                            String targetUser = tokens[1];
                            String content = tokens[2];
                            sendMessageToUser(targetUser, content);
                        } else if (command.equals("LOGOUT")) {
                            break; // Wychodzi z pętli i kończy połączenie
                        } else {
                            out.println("BŁĄD: Nieznana komenda. Użyj: SEND <odbiorca> <wiadomość> lub LOGOUT");
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Rozłączono klienta niespodziewanie.");
            } finally {
                disconnect();
            }
        }

        private void handleLogin(String login, String password) {
            // Sprawdza, czy taki użytkownik istnieje i czy hasło się zgadza
            if (userDatabase.containsKey(login) && userDatabase.get(login).equals(password)) {

                // Sprawdza, czy użytkownik nie jest już zalogowany z innego miejsca
                if (activeUsers.containsKey(login)) {
                    out.println("BŁĄD: Użytkownik jest już zalogowany.");
                } else {
                    loggedInUser = login;
                    activeUsers.put(login, this);
                    System.out.println(login + " zalogował/a się.");
                    out.println("SUKCES: Zalogowano jako " + login);
                }
            } else {
                out.println("BŁĄD: Nieprawidłowy login lub hasło.");
            }
        }

        private void sendMessageToUser(String targetUser, String content) {
            ClientHandler targetHandler = activeUsers.get(targetUser);

            if (targetHandler != null) {
                // Użytkownik jest zalogowany, wysyłamy wiadomość
                targetHandler.sendMessage("WIADOMOŚĆ OD [" + loggedInUser + "]: " + content);
                out.println("SYSTEM: Wiadomość wysłana do " + targetUser);
            } else {
                // Użytkownik nie jest zalogowany
                out.println("SYSTEM: Użytkownik " + targetUser + " jest w tej chwili offline lub nie istnieje.");
            }
        }

        // Metoda do odbierania wiadomości z innego wątku
        public void sendMessage(String msg) {
            out.println(msg);
        }

        private void disconnect() {
            if (loggedInUser != null) {
                activeUsers.remove(loggedInUser);
                System.out.println(loggedInUser + " wylogował/a się.");
            }
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Błąd podczas zamykania gniazda: " + e.getMessage());
            }
        }
    }
}
