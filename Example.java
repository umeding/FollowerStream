import java.io.*;

public class Example {

    private final static int BUFSIZ = 1024;

    private void tail(String filename) throws IOException {

        File file = new File(filename);
        try (FileFollowerStream fp = new FileFollowerStream(file)) {
            byte[] buffer = new byte[BUFSIZ];
            int nread;

            while((nread = fp.read(buffer)) > 0) {
                System.out.write(buffer, 0, nread);
                System.out.flush();
            }
        }
    }

    public static void main(String ... av) throws Exception {
        if(av.length != 1)
            throw new IOException("Must supply a path to a file to follow");

        new Example().tail(av[0]);
    }
}
