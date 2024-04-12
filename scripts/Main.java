/*
Copyright (c) 2023 Divested Computing Group

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
//import org.sqlite.*;
//import java.sql.*;

public class Main {

    private static BloomFilter<String> signaturesMD5 = null;
    private static BloomFilter<String> signaturesSHA1 = null;
    private static BloomFilter<String> signaturesSHA256 = null;

    private static int amtLinesValid = 0;
    private static int amtLinesInvalid = 0;

    private static int amtSignaturesReadMD5 = 0;
    private static int amtSignaturesReadSHA1 = 0;
    private static int amtSignaturesReadSHA256 = 0;

    private static int amtSignaturesAddedMD5 = 0;
    private static int amtSignaturesAddedSHA1 = 0;
    private static int amtSignaturesAddedSHA256 = 0;

    private static int amtPreviousSignaturesMD5 = 0;
    private static int amtPreviousSignaturesSHA1 = 0;
    private static int amtPreviousSignaturesSHA256 = 0;

    private static ArrayList<String> arrExclusions = new ArrayList<String>();

    public static void main(String[] args) {
        //isFileInNsrl("B61905308B336AD268A782790B661616");
        signaturesMD5 = BloomFilter.create(Funnels.stringFunnel(Charsets.US_ASCII), 6000000, 0.00001); //6m
        signaturesSHA1 = BloomFilter.create(Funnels.stringFunnel(Charsets.US_ASCII), 50000, 0.00001); //50k
        signaturesSHA256 = BloomFilter.create(Funnels.stringFunnel(Charsets.US_ASCII), 2000000, 0.00001); //2m

        System.out.println("Processing exclusions:");
        File[] exclusions = new File(args[0] + "../exclusions/").listFiles();
        Arrays.sort(exclusions);
        for (File exclusionDatabase : exclusions) {
            try {
                System.out.println("\t" + exclusionDatabase.getName());
                Scanner s = new Scanner(exclusionDatabase);
                while(s.hasNextLine()) {
                    String line = s.nextLine().trim().toLowerCase();
                    if(line.contains(":")) {
                        line = line.split(":")[0];
                    }
                    if(!line.startsWith("#") && isHexadecimal(line) && (line.length() == 32 || line.length() == 40 || line.length() == 64)) {
                        arrExclusions.add(line);
                        //System.out.println("\t\tAdded: " + line);
                    }
                }
                s.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.out.println("Loaded " + arrExclusions.size() + " excluded hashes");

        System.out.println("Processing signatures:");
        File[] databases = new File(args[0]).listFiles();
        File extras = new File(args[0] + "../extras/");
        if(extras.exists()) {
            databases = Stream.concat(Arrays.stream(databases), Arrays.stream(extras.listFiles())).toArray(File[]::new);
        }
        Arrays.sort(databases);
        for (File databaseLocation : databases) {
            if(databaseLocation.isFile()) {
                System.out.println("\t" + databaseLocation.getName());
                amtPreviousSignaturesMD5 = amtSignaturesAddedMD5;
                amtPreviousSignaturesSHA1 = amtSignaturesAddedSHA1;
                amtPreviousSignaturesSHA256 = amtSignaturesAddedSHA256;
                try {
                    BufferedReader reader;
                    if (databaseLocation.getName().endsWith(".gz")) {
                        reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(databaseLocation))));
                    } else {
                        reader = new BufferedReader(new FileReader(databaseLocation));
                    }
                    String line;
                    if (databaseLocation.getName().endsWith(".hdb") //.hdb/.hsb format: hash:size:name:version
                            || databaseLocation.getName().endsWith(".hsb")) {
                        while ((line = reader.readLine()) != null) {
                            if (line.length() > 0 && line.contains(":")) {
                                String[] lineS = line.trim().toLowerCase().split(":");
                                addChecked(lineS[0].trim(), true);
                            }
                        }
                    } else if (databaseLocation.getName().endsWith(".md5")
                            || databaseLocation.getName().endsWith(".sha1")
                            || databaseLocation.getName().endsWith(".sha256")
                            || databaseLocation.getName().endsWith(".hashes")) {//one signature per line
                        while ((line = reader.readLine()) != null) {
                            addChecked(line.trim().toLowerCase(), true);
                        }
                    } else if (databaseLocation.getName().endsWith(".loki")) {//.loki format: hash;comment
                        while ((line = reader.readLine()) != null) {
                            if (line.length() > 0 && line.contains(";")) {
                                String[] lineS = line.trim().toLowerCase().split(";");
                                addChecked(lineS[0].trim(), true);
                            }
                        }
                    } else if (databaseLocation.getName().endsWith(".txt")) {//best effort
                        while ((line = reader.readLine()) != null) {
                            addChecked(line.trim().toLowerCase(), false);
                        }
                    }
                    reader.close();
                    System.out.println("\t\tmd5: " + (amtSignaturesAddedMD5 - amtPreviousSignaturesMD5) + ", sha1: " + (amtSignaturesAddedSHA1 - amtPreviousSignaturesSHA1) + ", sha256: " + (amtSignaturesAddedSHA256 - amtPreviousSignaturesSHA256));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        System.out.println("Lines read: valid: " + amtLinesValid + ", invalid: " + amtLinesInvalid);
        System.out.println("Read count: md5: " + amtSignaturesReadMD5 + ", sha1: " + amtSignaturesReadSHA1 + ", sha256: " + amtSignaturesReadSHA256);
        System.out.println("Added count: md5: " + amtSignaturesAddedMD5 + ", sha1: " + amtSignaturesAddedSHA1 + ", sha256: " + amtSignaturesAddedSHA256);
        System.out.println("Approximate count: md5: " + signaturesMD5.approximateElementCount() + ", sha1: " + signaturesSHA1.approximateElementCount() + ", sha256: " + signaturesSHA256.approximateElementCount());
        System.out.println("Expected false postive rate: md5: " + signaturesMD5.expectedFpp() + ", sha1: " + signaturesSHA1.expectedFpp() + ", sha256: " + signaturesSHA256.expectedFpp());
        try {
            FileOutputStream fileSignaturesMD5 = new FileOutputStream(new File(args[0]) + "/hypatia-md5-bloom.bin");
            signaturesMD5.writeTo(fileSignaturesMD5);
            fileSignaturesMD5.close();

            FileOutputStream fileSignaturesSHA1 = new FileOutputStream(new File(args[0]) + "/hypatia-sha1-bloom.bin");
            signaturesSHA1.writeTo(fileSignaturesSHA1);
            fileSignaturesSHA1.close();

            FileOutputStream fileSignaturesSHA256 = new FileOutputStream(new File(args[0]) + "/hypatia-sha256-bloom.bin");
            signaturesSHA256.writeTo(fileSignaturesSHA256);
            fileSignaturesSHA256.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //Credit: https://stackoverflow.com/a/13667522
    private static final Pattern HEXADECIMAL_PATTERN = Pattern.compile("\\p{XDigit}+");

    private static boolean isHexadecimal(String input) {
        final Matcher matcher = HEXADECIMAL_PATTERN.matcher(input);
        return matcher.matches();
    }

    private static void addChecked(String potentialHash, boolean report) {
        if (!potentialHash.startsWith("#") && potentialHash.length() >= 4) {
            if (isHexadecimal(potentialHash)) {
                if(arrExclusions.contains(potentialHash)) {
                    System.out.println("\t\tSkipping excluded hash: " + potentialHash);
                    return;
                }
		//if(isFileInNsrl(potentialHash)) {
                //    return;
                //}
                if (potentialHash.length() == 32) {
                    if (signaturesMD5.put(potentialHash)) {
                        amtSignaturesAddedMD5++;
                    }
                    amtSignaturesReadMD5++;
                    amtLinesValid++;
                } else if (potentialHash.length() == 40) {
                    if (signaturesSHA1.put(potentialHash)) {
                        amtSignaturesAddedSHA1++;
                    }
                    amtSignaturesReadSHA1++;
                    amtLinesValid++;
                } else if (potentialHash.length() == 64) {
                    if (signaturesSHA256.put(potentialHash)) {
                        amtSignaturesAddedSHA256++;
                    }
                    amtSignaturesReadSHA256++;
                    amtLinesValid++;
                } else {
                    amtLinesInvalid++;
                    if(report) System.out.println("\t\tINVALID LENGTH: " + potentialHash);
                }
            } else {
                amtLinesInvalid++;
                if(report) System.out.println("\t\tNOT HEXADECIMAL: " + potentialHash);
            }
        }
    }

    //CREATE INDEX hashIndexMD5 ON FILE (md5); CREATE INDEX hashIndexSHA1 ON FILE (sha1); CREATE INDEX hashIndexSHA256 ON FILE (sha256);
    //CREATE INDEX hashIndex ON FILE (md5,sha1,sha256);
    /*private static String url = "jdbc:sqlite:RDS_2024.03.1_android_minimal.db";
    private static Connection connection = null;
    private static Statement statement = null;
    private static boolean isFileInNsrl(String hash) {
        String hashType = null;
        switch(hash.length()) {
            case 32:
                hashType = "md5";
                break;
            case 40:
                hashType ="sha1";
                break;
            case 64:
                hashType ="sha256";
                break;
            default:
                return false;
        }
        String sql = "select package_id,md5,sha1,sha256 from FILE where " + hashType + " = '" + hash.toUpperCase() + "';";
        try {
            if(connection == null || statement == null) {
                SQLiteConfig config = new SQLiteConfig();
                config.setReadOnly(true);
                config.setSharedCache(true);
                connection = DriverManager.getConnection(url, config.toProperties());
                statement = connection.createStatement();
            }
            ResultSet rs = statement.executeQuery(sql);
            if(rs.next()) {
                System.out.println("\t\tSkipping excluded NSRL hash: "+ rs.getString("md5").toLowerCase());
                System.out.println("\t\tSkipping excluded NSRL hash: " + rs.getString("sha1").toLowerCase());
                System.out.println("\t\tSkipping excluded NSRL hash: " + rs.getString("sha256").toLowerCase());
                ResultSet rsPkg = statement.executeQuery("select name from PKG where package_id = '" + rs.getString("package_id") + "';");
                if (rsPkg.next()) {
                    System.out.println("\t\t\tFrom: " + rsPkg.getString("name"));
                }
                return true;
            }
            return false;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }*/
}
