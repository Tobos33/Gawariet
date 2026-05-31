import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;

public class ChatServer {
    private static final int PORT = 12345;
    private static final String DB_FILE_PATH = "users.txt";
    private static final String FRIENDS_FILE_PATH = "friends.txt";

    private static final ConcurrentHashMap<String, String> userDatabase = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ClientHandler> activeUsers = new ConcurrentHashMap<>();

    // Klucz: "user1:user2" (zawsze w kolejności alfabetycznej), Wartość: "INVITED_BY_user1" lub "ACCEPTED"
    private static final ConcurrentHashMap<String, String> friendshipDatabase = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        loadUserDatabase();
        loadFriendshipDatabase();

        System.out.println("Uruchamianie serwera na porcie " + PORT + "...");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Serwer nasłuchuje na połączenia.");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Nowe połączenie: " + clientSocket.getInetAddress());

                ClientHandler clientHandler = new ClientHandler(clientSocket);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Błąd serwera: " + e.getMessage());
        }
    }

    private static void loadUserDatabase() {
        File dbFile = new File(DB_FILE_PATH);
        if (!dbFile.exists()) {
            createDefaultDatabaseFile(dbFile);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(dbFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    userDatabase.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Błąd ładowania użytkowników: " + e.getMessage());
        }
    }

    private static void loadFriendshipDatabase() {
        File friendsFile = new File(FRIENDS_FILE_PATH);
        if (!friendsFile.exists()) return; // Plik nie musi istnieć na starcie

        try (BufferedReader reader = new BufferedReader(new FileReader(friendsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                String[] parts = line.split(":", 3); // u1:u2:STATUS
                if (parts.length == 3) {
                    String key = getFriendshipKey(parts[0], parts[1]);
                    friendshipDatabase.put(key, parts[2]);
                }
            }
            System.out.println("Załadowano relacje znajomych z pliku.");
        } catch (IOException e) {
            System.err.println("Błąd ładowania listy znajomych: " + e.getMessage());
        }
    }

    // Zapisuje całą mapę znajomych do pliku (nadpisuje plik aktualnym stanem z pamięci RAM)
    private static synchronized void saveFriendshipsToFile() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(FRIENDS_FILE_PATH))) {
            writer.println("# Format: user1:user2:STATUS");
            for (Map.Entry<String, String> entry : friendshipDatabase.entrySet()) {
                writer.println(entry.getKey() + ":" + entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("Błąd zapisu listy znajomych do pliku: " + e.getMessage());
        }
    }

    private static synchronized boolean saveUserToFile(String login, String password) {
        try (FileWriter fw = new FileWriter(DB_FILE_PATH, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println(login + ":" + password);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void createDefaultDatabaseFile(File file) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("# Format: login:haslo");
            writer.println("adam:haslo123");
            writer.println("ewa:tajne456");
            writer.println("jan:qwerty");
        } catch (IOException e) {
            System.err.println("Nie utworzono domyślnego pliku bazy danych.");
        }
    }

    // Pomocnicza metoda generująca unikalny klucz dla pary użytkowników (sortowanie alfabetyczne)
    // Dzięki temu relacja "adam" i "ewa" zawsze ma klucz "adam:ewa", niezależnie kto kogo zaprasza
    private static String getFriendshipKey(String u1, String u2) {
        return u1.compareTo(u2) < 0 ? u1 + ":" + u2 : u2 + ":" + u1;
    }

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

                out.println("Witaj! Zaloguj się (LOGIN) lub zarejestruj (REGISTER).");

                String message;
                while ((message = in.readLine()) != null) {
                    String[] tokens = message.split(" ", 3);
                    String command = tokens[0].toUpperCase();

                    if (loggedInUser == null) {
                        if (command.equals("LOGIN") && tokens.length == 3) {
                            handleLogin(tokens[1], tokens[2]);
                        } else if (command.equals("REGISTER") && tokens.length == 3) {
                            handleRegister(tokens[1], tokens[2]);
                        } else {
                            out.println("BŁĄD: Musisz się najpierw zalogować.");
                        }
                    } else {
                        // Faza po zalogowaniu - nowe komendy
                        if (command.equals("SEND") && tokens.length == 3) {
                            sendMessageToUser(tokens[1], tokens[2]);
                        } else if (command.equals("INVITE") && tokens.length >= 2) {
                            handleInvite(tokens[1]);
                        } else if (command.equals("ACCEPT") && tokens.length >= 2) {
                            handleAccept(tokens[1]);
                        } else if (command.equals("FRIENDS")) {
                            handleListFriends();
                        } else if (command.equals("LOGOUT")) {
                            break;
                        } else {
                            out.println("BŁĄD: Nieznana komenda. Dostępne: SEND, INVITE, ACCEPT, FRIENDS, LOGOUT");
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
            if (userDatabase.containsKey(login) && userDatabase.get(login).equals(password)) {
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

        private void handleRegister(String login, String password) {
            if (login.contains(":") || password.contains(":") || login.contains(" ") || password.contains(" ")) {
                out.println("BŁĄD: Login i hasło nie mogą zawierać spacji ani dwukropków.");
                return;
            }
            if (userDatabase.containsKey(login)) {
                out.println("BŁĄD: Użytkownik o loginie '" + login + "' już istnieje.");
                return;
            }
            if (saveUserToFile(login, password)) {
                userDatabase.put(login, password);
                out.println("SUKCES: Rejestracja pomyślna. Możesz się teraz zalogować.");
            } else {
                out.println("BŁĄD: Błąd serwera przy rejestracji.");
            }
        }

        private void handleInvite(String targetUser) {
            if (targetUser.equals(loggedInUser)) {
                out.println("BŁĄD: Nie możesz zaprosić samego siebie.");
                return;
            }
            if (!userDatabase.containsKey(targetUser)) {
                out.println("BŁĄD: Użytkownik '" + targetUser + "' nie istnieje.");
                return;
            }

            String key = getFriendshipKey(loggedInUser, targetUser);
            String currentStatus = friendshipDatabase.get(key);

            if (currentStatus != null) {
                if (currentStatus.equals("ACCEPTED")) {
                    out.println("SYSTEM: Jesteście już znajomymi!");
                } else if (currentStatus.equals("INVITED_BY_" + loggedInUser)) {
                    out.println("SYSTEM: Już wysłałeś zaproszenie do tego użytkownika. Czekaj na odpowiedź.");
                } else {
                    out.println("SYSTEM: Ten użytkownik już Cię zaprosił! Użyj komendy ACCEPT " + targetUser);
                }
                return;
            }

            // Tworzenie zaproszenia
            friendshipDatabase.put(key, "INVITED_BY_" + loggedInUser);
            saveFriendshipsToFile();
            out.println("SYSTEM: Zaproszenie wysłane do użytkownika " + targetUser);

            // Jeśli zapraszany jest online, powiadom go natychmiast
            ClientHandler targetHandler = activeUsers.get(targetUser);
            if (targetHandler != null) {
                targetHandler.sendMessage("POWIADOMIENIE: Użytkownik [" + loggedInUser + "] wysłał Ci zaproszenie do znajomych!");
            }
        }

        private void handleAccept(String targetUser) {
            String key = getFriendshipKey(loggedInUser, targetUser);
            String currentStatus = friendshipDatabase.get(key);

            if (currentStatus == null || !currentStatus.equals("INVITED_BY_" + targetUser)) {
                out.println("BŁĄD: Nie masz oczekującego zaproszenia od użytkownika " + targetUser);
                return;
            }

            // Zmiana statusu na zaakceptowany
            friendshipDatabase.put(key, "ACCEPTED");
            saveFriendshipsToFile();
            out.println("SYSTEM: Zaakceptowano zaproszenie. Teraz możecie ze sobą pisać!");

            // Powiadomienie drugiego użytkownika, jeśli jest online
            ClientHandler targetHandler = activeUsers.get(targetUser);
            if (targetHandler != null) {
                targetHandler.sendMessage("POWIADOMIENIE: Użytkownik [" + loggedInUser + "] zaakceptował Twoje zaproszenie do znajomych!");
            }
        }

        private void handleListFriends() {
            out.println("=== TWOI ZNAJOMI ===");
            boolean clean = true;
            for (Map.Entry<String, String> entry : friendshipDatabase.entrySet()) {
                String[] users = entry.getKey().split(":");
                String u1 = users[0];
                String u2 = users[1];

                // Sprawdzamy, czy ta relacja dotyczy zalogowanego użytkownika
                if (u1.equals(loggedInUser) || u2.equals(loggedInUser)) {
                    String friend = u1.equals(loggedInUser) ? u2 : u1;
                    String status = entry.getValue();

                    if (status.equals("ACCEPTED")) {
                        String onlineStatus = activeUsers.containsKey(friend) ? "[ONLINE]" : "[OFFLINE]";
                        out.println("* " + friend + " " + onlineStatus);
                    } else if (status.equals("INVITED_BY_" + loggedInUser)) {
                        out.println("* " + friend + " (Wysłałeś zaproszenie - oczekiwanie)");
                    } else {
                        out.println("* " + friend + " (Zaprosił Cię! Wpisz: ACCEPT " + friend + ")");
                    }
                    clean = false;
                }
            }
            if (clean) out.println("(brak znajomych i zaproszeń)");
            out.println("====================");
        }

        private void sendMessageToUser(String targetUser, String content) {
            String key = getFriendshipKey(loggedInUser, targetUser);
            String status = friendshipDatabase.get(key);

            // Blokada wysyłania wiadomości bez statusu ACCEPTED
            if (status == null || !status.equals("ACCEPTED")) {
                out.println("BŁĄD: Nie możesz wysłać wiadomości do " + targetUser + ". Nie jesteście znajomymi. Najpierw wyślij zaproszenie (INVITE).");
                return;
            }

            ClientHandler targetHandler = activeUsers.get(targetUser);
            if (targetHandler != null) {
                targetHandler.sendMessage("WIADOMOŚĆ OD [" + loggedInUser + "]: " + content);
                out.println("SYSTEM: Wiadomość wysłana do " + targetUser);
            } else {
                out.println("SYSTEM: Użytkownik " + targetUser + " jest w tej chwili offline.");
            }
        }

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