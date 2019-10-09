package org.molgenis;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

public class FileCreator {

  private static void createFile(String fileName, String header) throws IOException {
    FileWriter outputFile = new FileWriter(fileName);
    try (CSVPrinter printer = new CSVPrinter(outputFile, CSVFormat.TDF.withQuote(null))) {
      printer.printRecord(header);
    }
  }

  public static void createOutputFile(String output, String headers) {
    try {
      Path path = Paths.get(output);
      Files.deleteIfExists(path);
      createFile(output, headers);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}