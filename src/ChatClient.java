import java.io.*;
import java.net.*;
import java.util.Scanner;

public class ChatClient {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 12345;

    public static void main(String[] args) {
        System.out.println("Łączenie z serwerem czatu...");

        try (Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("Połączono pomyślnie!");

            // Wątek odpowiedzialny za odbieranie wiadomości z serwera
            Thread receiverThread = new Thread(() -> {
                try {
                    String serverMessage;
                    // Czyta linijka po linijce to, co przysyła serwer
                    while ((serverMessage = in.readLine()) != null) {
                        System.out.println("\n" + serverMessage);
                        System.out.println("> "); // Przywraca znak zachęty po odebraniu wiadomości
                    }
                } catch (IOException e) {
                    System.out.println("\nPołączenie z serwerem zostało przerwane.");
                }
            });
            receiverThread.start();

            // Główny wątek (pętla) odpowiedzialny za wysyłanie wiadomości do serwera
            System.out.print("> ");
            while (true) {
                String userInput = scanner.nextLine();

                // Wysyłamy komendę do serwera
                out.println(userInput);

                // Jeśli użytkownik wpisał LOGOUT, kończymy działanie klienta
                if (userInput.equalsIgnoreCase("LOGOUT")) {
                    System.out.println("Wylogowywanie i zamykanie aplikacji...");
                    break;
                }
                System.out.println("> ");
            }

        } catch (UnknownHostException e) {
            System.err.println("Nie znaleziono hosta: " + SERVER_ADDRESS);
        } catch (IOException e) {
            System.err.println("Błąd I/O podczas połączenia: " + e.getMessage());
        }
    }
}