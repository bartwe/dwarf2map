import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class Reader {

    PrintStream out;
    LinkedList<String> input = new LinkedList<String>();
    int imageBase = 0x00400000;
    ArrayList<Record> records = new ArrayList<Record>();

    public List<Record> process(int addressStart, int addressEnd) throws Exception {
        Runtime rt = Runtime.getRuntime();
        Process p = rt.exec("c:/mingw/bin/addr2line.exe -f -a -e c:/hg/starbound/dist/starbound.exe -s");
        final InputStream in = p.getInputStream();
        out = new PrintStream(p.getOutputStream(), true);
        final InputStream err = p.getErrorStream();

        Thread thread = new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                while (true) {
                    String s = null;
                    while (true) {
                        try {
                            int c = in.read();
                            if (c < 0)
                                return;
                            if (c == 13)
                                continue;
                            if (c == 10)
                                break;
                            sb.append((char) c);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    s = sb.toString();
                    sb.setLength(0);
                    synchronized (input) {
                        input.addLast(s);
                        input.notifyAll();
                    }
                }
            }
        });
        thread.start();

        Thread errthread = new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                while (true) {
                    String s = null;
                    while (true) {
                        try {
                            int c = err.read();
                            if (c < 0)
                                return;
                            if (c == 13)
                                continue;
                            if (c == 10)
                                break;
                            sb.append((char) c);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    System.err.println(sb.toString());
                    sb.setLength(0);
                }
            }
        });
        errthread.start();

        scan(addressStart, addressEnd);

        p.destroy();
        thread.join();

        return records;
    }

    void scan(int staraddress, int endaddress) throws Exception {
        Record currentRecord = null;
        int address = staraddress;
        while (address < endaddress) {
            if ((address & 0xfffff000) == address)
                System.out.println("Scanning: " + String.format("%08x", Integer.valueOf(address)));
            Record r = readAddress(address);
            if ((r == null) && (currentRecord != null)) {
                records.add(currentRecord);
                currentRecord = null;
            } else if (currentRecord == null) {
                currentRecord = r;
            } else {
                if ((r.line == currentRecord.line) && (r.file.equals(currentRecord.file)) && (r.function.equals(currentRecord.function))) {
                    currentRecord.range = r.address - currentRecord.address;
                } else {
                    records.add(currentRecord);
                    currentRecord = r;
                }
            }

            address++;
            if (currentRecord == null) {
//                address = address & 0xfffffff7 + 8;
            }
        }
    }

    int rateEntry(String function, String location) {
        int rate = 0;
        if (location.contains(".cpp"))
            rate += 2;
        if (location.contains(".hpp"))
            rate += 1;
        if (location.contains("Star"))
            rate++;
        return rate;
    }

    HashMap<String, String> mapping = new HashMap<String, String>();
    HashMap<String, Integer> known = new HashMap<String, Integer>();

    String demangle(String function) {
        String result = mapping.get(function);
        if (result != null)
            return result;
        result = innerDemange(function);
        if (result.length() == 0)
            result = function;
        Integer count = known.get(result);
        if (count != null) {
            known.put(result, count + 1);
            result = result + (count + 1);
        } else
            known.put(result, 1);
        mapping.put(function, result);
        return result;
    }

    String innerDemange(String function) {
        if (!function.startsWith("_ZN"))
            return function;
        String input = function;
        if (function.startsWith("_ZNK")) {
            if (function.startsWith("_ZNKSt"))
                function = function.substring(6);
            else
                function = function.substring(4);
        } else {
            if (function.startsWith("_ZNSt"))
                function = function.substring(5);
            else
                function = function.substring(3);
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (true) {
            if (function.startsWith("E") || function.length() == 0)
                return sb.toString();
            int len = 0;
            int skip = 1;
            if (Character.isDigit(function.charAt(0))) {
                len = function.charAt(0) - '0';
                skip = 1;
                if ((function.length() >= 2) && Character.isDigit(function.charAt(1))) {
                    len = len * 10 + (function.charAt(1) - '0');
                    skip = 2;
                }
                if (first)
                    first = false;
                else
                    sb.append(".");
                if (len + skip > function.length())
                    throw new RuntimeException("Unrecognized input: " + input);
                sb.append(function.substring(skip, len + skip));
            } else
                return sb.toString();
            function = function.substring(len + skip);
        }
    }

    Record readAddress(int address) throws Exception {
        String key = String.format("0x%08x", Integer.valueOf(address));
        out.println(key);
        while (true) {
            String s = readLine();
            if (s.equals(key))
                break;
            System.err.println("TRASH: " + s);
        }
        String function = readLine();
        String location = readLine();
        if (function.equals("??"))
            return null;
        String innerFunction = function;
//        int rating = rateEntry(function, location);
        out.println("0");
        while (true) {
            String f = readLine();
            String l = readLine();

            if (f.equals("0x00000000")) {
                readLine();
                break;
            }
//            int r = rateEntry(f, l);
  //          if (r > rating) {
                function = f;
                location = l;
//                rating = r;
//            }
        }
        Record record = new Record();
        record.address = address;
        record.range = 1;
        if (function != innerFunction)
            record.function = (demangle(function) + ":" + demangle(innerFunction) + ":" + location).intern();
        else
            record.function = (demangle(function) + "::" + location).intern();
        String[] bits = location.split(":", 2);
        record.file = bits[0].intern();
        record.line = Integer.parseInt(bits[1]);
        return record;
    }

    String readLine() throws InterruptedException {
        while (true) {
            synchronized (input) {
                if (input.size() == 0)
                    input.wait();
                else {
                    String i = input.removeFirst();
                    return i;
                }
            }
        }
    }
}
