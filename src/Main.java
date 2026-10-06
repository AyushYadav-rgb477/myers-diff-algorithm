import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws IOException {
        boolean known = args.length == 3 && (args[0].equals("lines") || args[0].equals("highlight"));
        if (!known) {
            System.err.println("usage: Main lines|highlight A_PATH B_PATH");
            System.exit(2);
        }
        String command = args[0];

        // Stage 1: read both files as raw bytes. A missing file -> exit 2, print nothing.
        byte[] aBytes;
        byte[] bBytes;
        try {
            aBytes = Files.readAllBytes(Path.of(args[1]));
            bBytes = Files.readAllBytes(Path.of(args[2]));
        } catch (IOException e) {
            System.err.println("cannot read input: " + e.getMessage());
            System.exit(2);
            return;
        }

        List<String> a = splitLines(aBytes);
        List<String> b = splitLines(bBytes);

        // Give every distinct line a number, so the diff compares ints, not strings.
        Map<String, Integer> ids = new HashMap<>();
        int[] aIds = toIds(a, ids);
        int[] bIds = toIds(b, ids);

        // Stage 2 + 3: Myers diff -> which lines of A are deleted, which lines of B are inserted.
        boolean[] deleted = new boolean[a.size()];
        boolean[] inserted = new boolean[b.size()];
        new Myers(aIds, bIds, deleted, inserted).run();

        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        printListing(a, b, deleted, inserted, command.equals("highlight"), out);
        out.flush();
    }

    // ---------------------------------------------------------------- Stage 1

    /**
     * Split on '\n' only; '\r' stays part of the line. A final empty piece
     * (file ends with '\n') is dropped. ISO-8859-1 maps each byte to one char,
     * so the String holds the exact bytes and converts back without loss.
     */
    static List<String> splitLines(byte[] data) {
        String text = new String(data, StandardCharsets.ISO_8859_1);
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    static int[] toIds(List<String> lines, Map<String, Integer> ids) {
        int[] result = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Integer id = ids.get(lines.get(i));
            if (id == null) {
                id = ids.size();
                ids.put(lines.get(i), id);
            }
            result[i] = id;
        }
        return result;
    }

    // ---------------------------------------------------------- Stage 2 + 3

    /**
     * Myers' O(ND) diff, linear-space version (Myers 1986, section 4b).
     * Finds a shortest edit script between a and b and records it as
     * deleted[i] (a[i] removed) and inserted[j] (b[j] added).
     */
    static final class Myers {
        private final int[] a, b;
        private final boolean[] deleted, inserted;
        private final int[] vf, vb; // furthest x per diagonal, forward and backward
        private final int offset;   // shifts diagonal k (can be negative) into an array index

        Myers(int[] a, int[] b, boolean[] deleted, boolean[] inserted) {
            this.a = a;
            this.b = b;
            this.deleted = deleted;
            this.inserted = inserted;
            // Backward diagonals sit around delta = N - M, so |k| can reach about 1.5 * (N + M).
            this.offset = 2 * (a.length + b.length) + 2;
            this.vf = new int[2 * offset + 2];
            this.vb = new int[2 * offset + 2];
        }

        void run() {
            compare(0, a.length, 0, b.length);
        }

        /** Diff a[aLo..aHi) against b[bLo..bHi). */
        private void compare(int aLo, int aHi, int bLo, int bHi) {
            // Equal lines at the start and end are never part of the edit script.
            while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
                aLo++;
                bLo++;
            }
            while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
                aHi--;
                bHi--;
            }
            if (aLo == aHi) {
                for (int j = bLo; j < bHi; j++) inserted[j] = true;
                return;
            }
            if (bLo == bHi) {
                for (int i = aLo; i < aHi; i++) deleted[i] = true;
                return;
            }
            // Find a point (x, y) that lies on a shortest path, then solve both halves.
            long split = middle(aLo, aHi, bLo, bHi);
            int x = (int) (split >>> 32);
            int y = (int) split;
            compare(aLo, x, bLo, y);
            compare(x, aHi, y, bHi);
        }

        /**
         * Run the greedy search forward from (0,0) and backward from (N,M) at
         * the same time, one edit at a time. When they meet on a diagonal, the
         * meeting point is on a shortest edit path. Returns it as (x << 32 | y)
         * in absolute coordinates.
         */
        private long middle(int aLo, int aHi, int bLo, int bHi) {
            int n = aHi - aLo;
            int m = bHi - bLo;
            int delta = n - m;
            boolean odd = (delta & 1) != 0;
            int max = (n + m + 1) / 2;

            vf[offset + 1] = 0;
            vb[offset + delta - 1] = n; // seed for the backward k = delta, d = 0 step

            for (int d = 0; d <= max; d++) {
                // Forward: furthest-reaching d-path on each diagonal k = x - y.
                for (int k = -d; k <= d; k += 2) {
                    int x;
                    if (k == -d || (k != d && vf[offset + k - 1] < vf[offset + k + 1])) {
                        x = vf[offset + k + 1];         // step down: insert b[y]
                    } else {
                        x = vf[offset + k - 1] + 1;     // step right: delete a[x]
                    }
                    int y = x - k;
                    while (x < n && y < m && a[aLo + x] == b[bLo + y]) { // snake
                        x++;
                        y++;
                    }
                    vf[offset + k] = x;
                    if (odd && k >= delta - (d - 1) && k <= delta + (d - 1) && x >= vb[offset + k]) {
                        return ((long) (aLo + x) << 32) | (bLo + y);
                    }
                }
                // Backward: furthest-reaching d-path from (n, m), diagonals centred on delta.
                for (int c = -d; c <= d; c += 2) {
                    int k = delta + c;
                    int x;
                    if (c == d || (c != -d && vb[offset + k - 1] < vb[offset + k + 1])) {
                        x = vb[offset + k - 1];         // step up: insert b[y-1]
                    } else {
                        x = vb[offset + k + 1] - 1;     // step left: delete a[x-1]
                    }
                    int y = x - k;
                    while (x > 0 && y > 0 && a[aLo + x - 1] == b[bLo + y - 1]) { // snake
                        x--;
                        y--;
                    }
                    vb[offset + k] = x;
                    if (!odd && k >= -d && k <= d && x <= vf[offset + k]) {
                        return ((long) (aLo + x) << 32) | (bLo + y);
                    }
                }
            }
            throw new IllegalStateException("unreachable: paths always meet");
        }
    }

    // ------------------------------------------------------------- Stage 4

    /**
     * Walk A and B together. Equal lines print as ' '. Each change block
     * prints all its '-' lines first, then its '+' lines. In highlight mode
     * the i-th '-' pairs with the i-th '+', and a '?' line follows that '+'.
     */
    static void printListing(List<String> a, List<String> b, boolean[] deleted, boolean[] inserted,
                             boolean highlight, OutputStream out) throws IOException {
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && !deleted[i] && !inserted[j]) {
                writeLine(out, ' ', a.get(i));
                i++;
                j++;
                continue;
            }
            int i0 = i;
            while (i < a.size() && deleted[i]) i++;
            int j0 = j;
            while (j < b.size() && inserted[j]) j++;

            for (int p = i0; p < i; p++) writeLine(out, '-', a.get(p));
            for (int q = j0; q < j; q++) {
                writeLine(out, '+', b.get(q));
                int p = i0 + (q - j0); // the '-' line paired with this '+'
                if (highlight && p < i) {
                    writeAscii(out, highlightLine(a.get(p), b.get(q)));
                }
            }
        }
    }

    static void writeLine(OutputStream out, char prefix, String line) throws IOException {
        out.write(prefix);
        out.write(line.getBytes(StandardCharsets.ISO_8859_1));
        out.write('\n');
    }

    static void writeAscii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
        out.write('\n');
    }

    // ---------------------------------------------------------- Stage 5 + 6

    /**
     * Character-level Myers on the code points of two paired lines.
     * Returns "? <old ranges> | <new ranges>", 0-based, end-exclusive,
     * or "." for a side with nothing highlighted.
     */
    static String highlightLine(String oldRaw, String newRaw) {
        int[] oldCp = codePoints(oldRaw);
        int[] newCp = codePoints(newRaw);
        boolean[] deleted = new boolean[oldCp.length];
        boolean[] inserted = new boolean[newCp.length];
        new Myers(oldCp, newCp, deleted, inserted).run();
        return "? " + ranges(deleted) + " | " + ranges(inserted);
    }

    /** The raw bytes of a line, decoded as UTF-8, as an array of code points. */
    static int[] codePoints(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        return new String(bytes, StandardCharsets.UTF_8).codePoints().toArray();
    }

    /** Turn marked positions into merged ranges, e.g. 2-3,5-9. */
    static String ranges(boolean[] marked) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        while (k < marked.length) {
            if (!marked[k]) {
                k++;
                continue;
            }
            int start = k;
            while (k < marked.length && marked[k]) k++;
            if (sb.length() > 0) sb.append(',');
            sb.append(start).append('-').append(k);
        }
        return sb.length() == 0 ? "." : sb.toString();
    }
}