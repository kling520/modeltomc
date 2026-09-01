package org.example;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            new WebUiServer().start();
            return;
        }

        Config config = Config.fromArgs(args);
        new Converter(config).run();
    }
}
