import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;

public class dwarf2map {

    public static void main(String[] args) {
        try {
            dwarf2map instance = new dwarf2map();
            instance.main();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    ArrayList<List<Record>> records = new ArrayList<List<Record>>();
    HashMap<String, ArrayList<Record>> byFileName = new HashMap<String, ArrayList<Record>>();
    HashMap<String, HashMap<String, Record>> byFunctionName = new HashMap<String, HashMap<String, Record>>();
    ArrayList<Record> allRecords = new ArrayList<Record>();
    int highestAddress;
    int imageBase = 0x00400000;
    boolean newRecords;
    int scannedChunks = 0;

    public void main() throws Exception {
        int address = imageBase;
        final int step = 0x10000;
        final dwarf2map parent = this;
        Thread[] workers = new Thread[7];
        int idleSteps = -workers.length;
        LinkedList<Thread> orderedWaitQueue = new LinkedList<Thread>();
        outer:
        while (true) {
            for (int i = 0; i < workers.length; i++) {
                if (workers[i] == null) {
                    synchronized (this) {
                        if (!newRecords)
                            idleSteps++;
                        else
                            idleSteps = 0;
                        newRecords = false;
                    }
                    final int a = address;
                    workers[i] = new Thread(new Runnable() {
                        public void run() {
                            Reader reader = new Reader();
                            try {
                                List<Record> result = reader.process(a, a + step);
                                synchronized (parent) {
                                    if (result.size() > 0)
                                        records.add(result);
                                    newRecords = result.size() > 0;
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });
                    orderedWaitQueue.add(workers[i]);
                    workers[i].start();
                    address += step;
                    scannedChunks++;
                }
            }

            while (!orderedWaitQueue.peek().isAlive()) {
                orderedWaitQueue.pop();
                synchronized (this) {
                    if (newRecords)
                        idleSteps = 0;
                    newRecords = false;
                }
            }

            while (idleSteps >= 2) {
                if (orderedWaitQueue.isEmpty())
                    break outer;
                if (orderedWaitQueue.peek().isAlive())
                    orderedWaitQueue.peek().join(10);
                else
                    orderedWaitQueue.pop();
                synchronized (this) {
                    if (newRecords)
                        idleSteps = 0;
                    newRecords = false;
                }
            }

            Thread.sleep(10);

            for (int i = 0; i < workers.length; i++) {
                if ((workers[i] != null) && (!workers[i].isAlive())) {
                    workers[i].join();
                    workers[i] = null;
                }
            }

        }

        for (int i = 0; i < workers.length; i++) {
            if (workers[i] != null)
                workers[i].join();
        }

        System.out.println("Combining " + records.size() + " result chunks, out of " + scannedChunks + " scanned chunks.");

        int count = 0;
        for (List<Record> records1 : records)
            for (Record record : records1) {
                addRecord(record);
                count++;
            }
        System.out.println("Generating mapfile from " + count + " records.");

        generateMapFile();
        System.out.println("Done.");
    }

    void generateMapFile() throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream("c:/hg/starbound/dist/starbound.map"));
/*
        out.print("\r\n" +
                " Start         Length     Name                   Class\r\n" +
                " 0001:00000000 " + String.format("%08x", Integer.valueOf(highestAddress + 16)) + "H .text                   CODE\r\n" +
                "\r\n" +
                "\r\n" +
                "Detailed map of segments\r\n" +
                "\r\n" +
                " 0001:00000000 " + String.format("%08x", Integer.valueOf(highestAddress + 16)) + " C=CODE     S=.text    G=(none)   M=Starbound   ACBP=A9\r\n" +
                "\r\n" +
                "\r\n" +
                "  Address         Publics by Name\r\n" +
                "\r\n");
        */
        /*
        ArrayList<String> functionName = new ArrayList<String>(byFunctionName.keySet());
//        Collections.sort(functionName);
        HashMap<Integer, String> publicsByValue = new HashMap<Integer, String>();
        int recCount = 0;
        for (String s : functionName) {
            HashMap<String, Record> entries = byFunctionName.get(s);
            for (Record r : entries.values()) {
                Integer intKey = Integer.valueOf(r.address - imageBase);
                String key = String.format("%08x", intKey);
                String entry = " 0001:" + key + "       " + r.function + "\r\n";
                publicsByValue.put(intKey, entry);
                //              out.print(entry);
                recCount++;
            }
        }

        System.out.println("Writing " + publicsByValue.size() + " symbols from " + recCount + " function records.");

        out.print("\r\n" +
                "\r\n" +
                "  Address         Publics by Value\r\n" +
                "\r\n");
        ArrayList<Integer> byValue = new ArrayList<Integer>(publicsByValue.keySet());
        Collections.sort(byValue);
        for (Integer s : byValue) {
            String r = publicsByValue.get(s);
            out.print(r);
        }
        out.print("\r\n" +
                "\r\n");
        */
        out.print(
                "  Address         Publics by Value\r\n" +
                        "\r\n");
        Collections.sort(allRecords, new Comparator<Record>() {
            public int compare(Record o1, Record o2) {
                int c = Integer.compare(o1.address, o2.address);
                if (c != 0)
                    return c;
                c = Integer.compare(o1.line, o2.line);
                if (c != 0)
                    return c;
                return o1.function.compareTo(o2.function);
            }
        });
        for (Record r : allRecords) {
            Integer intKey = Integer.valueOf(r.address - imageBase);
            String key = String.format("%08x", intKey);
            String entry = " 0001:" + key + "       " + r.function + "\r\n";
            out.print(entry);
        }
        /*
        for (String file : byFileName.keySet()) {
            ArrayList<Record> records = byFileName.get(file);
            Collections.sort(records, new Comparator<Record>() {
                public int compare(Record o1, Record o2) {
                    return Integer.compare(o1.address, o2.address);
                }
            });

            out.print("Line numbers for TBN(" + file + ") segment .text\r\n" +
                    "\r\n");

            int count = 0;

            for (Record r : records) {
                out.print(String.format("% 6d", Integer.valueOf(r.line)));
                out.print(" 0001:");
                out.print(String.format("%08x", Integer.valueOf(r.address - imageBase)));
                count++;
                if (count == 4) {
                    count = 0;
                    out.print("\r\n");
                }
            }

            if (count != 0)
                out.print("\r\n");
            out.print("\r\n");
        }

        out.print("Bound resource files\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "Program entry point at 0001:00000000\r\n");

        */
        out.close();
    }

    public void addRecord(Record record) {
        if (record.address + record.range > highestAddress)
            highestAddress = record.address + record.range;
        allRecords.add(record);
        ArrayList<Record> _byFileName = byFileName.get(record.file);
        if (_byFileName == null) {
            _byFileName = new ArrayList<Record>();
            byFileName.put(record.file, _byFileName);
        }
        _byFileName.add(record);

        HashMap<String, Record> _byFunctionName = byFunctionName.get(record.function);
        if (_byFunctionName == null) {
            _byFunctionName = new HashMap<String, Record>();
            byFunctionName.put(record.function, _byFunctionName);
        }
        Record current = _byFunctionName.get(record.file);
        if (current == null)
            _byFunctionName.put(record.file, record);
        else if ((current.line > record.line) || ((current.line == record.line) && (current.address > record.address)))
//        else if (current.address > record.address)
            _byFunctionName.put(record.file, record);
    }
}
