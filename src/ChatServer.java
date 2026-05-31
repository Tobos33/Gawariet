import java.io.*;
import java.net.*;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;

public class ChatServer {
    // private static final int PORT = 12345;

    // Prosta, wbudowana "baza danych" użytkowników (login -> hasło)
    private static final ConcurrentHashMap<String, String> userDatabase = new ConcurrentHashMap<>();

    // Przechowuje aktualnie zalogowanych użytkowników (login -> handler obsługujący połączenie)
    private static final ConcurrentHashMap<String, ClientHandler> activeUsers = new ConcurrentHashMap<>();
    
 // W klasie ChatServer
    private static final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> friendsDatabase = new ConcurrentHashMap<>();

    // W metodzie main (przykładowe relacje)

    public static void main(String[] args) throws IOException {
    	
    	Properties config = new Properties();
        
        FileInputStream file = new FileInputStream("Config.properties");
        config.load(file);
        
        int PORT = Integer.valueOf(config.getProperty("PORT"));
        
        // Inicjalizacja przykładowych użytkowników
        userDatabase.put("adam", "haslo123");
        userDatabase.put("ewa", "tajne456");
        userDatabase.put("jan", "qwerty");
        userDatabase.put("marianka", "haslo123");
        userDatabase.put("szymon", "tajne456");
        userDatabase.put("pawel", "qwerty");
        
        friendsDatabase.put("adam", new CopyOnWriteArraySet<>(Set.of("ewa","pawel")));
        friendsDatabase.put("ewa", new CopyOnWriteArraySet<>(Set.of("adam", "jan")));
        friendsDatabase.put("jan", new CopyOnWriteArraySet<>(Set.of("ewa")));
        friendsDatabase.put("marianka", new CopyOnWriteArraySet<>(Set.of("ewa","szymon","adam")));
        friendsDatabase.put("szymon", new CopyOnWriteArraySet<>(Set.of("marianka", "jan","pawel","ewa")));
        friendsDatabase.put("pawel", new CopyOnWriteArraySet<>(Set.of("ewa","szymon","ewa")));

        System.out.println("Uruchamianie serwera na porcie " + PORT + "...");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Serwer nasluchuje na polaczenia.");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Nowe polaczenie: " + clientSocket.getInetAddress());

                // Tworzenie i uruchamianie nowego wątku dla klienta
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Blad serwera: " + e.getMessage());
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

                out.println("Witaj na serwerze! Zaloguj sie uzywajac komendy: LOGIN <twoj_login> <twoje_haslo>");

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
                            out.println("BLAD: Musisz się najpierw zalogować. Uzyj: LOGIN <login> <haslo>");
                        }
                    }
                    // Faza po zalogowaniu
                    else {
                        if (command.equals("SEND") && tokens.length == 3) {
                            String targetUser = tokens[1];
                            String content = tokens[2];
                            sendMessageToUser(targetUser, content);
                        } else if (command.equals("GET_FRIENDS")) {
                            Set<String> friends = friendsDatabase.get(loggedInUser);
                            if (friends == null || friends.isEmpty()) {
                                out.println("FRIENDS_LIST: brak znajomych");
                            } else {
                                StringBuilder sb = new StringBuilder("FRIENDS_LIST:");
                                for (String friend : friends) {
                                    boolean isOnline = activeUsers.containsKey(friend);
                                    sb.append(friend).append("(").append(isOnline ? "ONLINE" : "OFFLINE").append("),");
                                }
                                out.println(sb.toString()); // Serwer wysyła np. "FRIENDS_LIST:ewa(ONLINE),jan(OFFLINE),"
                            }
                        } else if (command.equals("LOGOUT")) {
                            break; // Wychodzi z pętli i kończy połączenie
                        } else {
                            out.println("BLAD: Nieznana komenda. Uzyj: SEND <odbiorca> <wiadomosc> lub LOGOUT");
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Rozlaczono klienta niespodziewanie.");
            } finally {
                disconnect();
            }
        }

        private void handleLogin(String login, String password) {
            // Sprawdza, czy taki użytkownik istnieje i czy hasło się zgadza
            if (userDatabase.containsKey(login) && userDatabase.get(login).equals(password)) {

                // Sprawdza, czy użytkownik nie jest już zalogowany z innego miejsca
                if (activeUsers.containsKey(login)) {
                    out.println("BLAD: Uzytkownik jest juz zalogowany.");
                } else {
                    loggedInUser = login;
                    activeUsers.put(login, this);
                    System.out.println(login + " zalogowal/a się.");
                    out.println("SUKCES: Zalogowano jako " + login);
                }
            } else {
                out.println("BLAD: Nieprawidlowy login lub haslo.");
            }
        }

        private void sendMessageToUser(String targetUser, String content) {
            ClientHandler targetHandler = activeUsers.get(targetUser);
            
            Set<String> adamsFriends = friendsDatabase.get(loggedInUser);
            if (adamsFriends == null || !adamsFriends.contains(targetUser)) {
                out.println("BlAD: Nie mozesz wyslac wiadomosci do " + targetUser + " – nie jestescie znajomymi.");
                return;
            }else if (targetHandler != null) {
                // Użytkownik jest zalogowany, wysyłamy wiadomość
                targetHandler.sendMessage("WIADOMOŚĆ OD [" + loggedInUser + "]: " + content);
                out.println("SYSTEM: Wiadomosc wysłana do " + targetUser);
            } else {
                // Użytkownik nie jest zalogowany
                out.println("SYSTEM: Uzytkownik " + targetUser + " jest w tej chwili offline lub nie istnieje.");
            }
        }

        // Metoda do odbierania wiadomości z innego wątku
        public void sendMessage(String msg) {
            out.println(msg);
        }

        private void disconnect() {
            if (loggedInUser != null) {
                activeUsers.remove(loggedInUser);
                System.out.println(loggedInUser + " wylogowal/a sie.");
            }
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Blad podczas zamykania gniazda: " + e.getMessage());
            }
        }
    }
}
