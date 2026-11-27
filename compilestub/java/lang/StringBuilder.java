package java.lang;
/** Compile-time only: javac -target 8 emits StringBuilder for string '+'; retro.py rewrites it to StringBuffer. */
public final class StringBuilder {
    public StringBuilder() {}
    public StringBuilder(String s) {}
    public StringBuilder append(String s) { return this; }
    public StringBuilder append(Object o) { return this; }
    public StringBuilder append(char c) { return this; }
    public StringBuilder append(int i) { return this; }
    public StringBuilder append(long l) { return this; }
    public StringBuilder append(float f) { return this; }
    public StringBuilder append(double d) { return this; }
    public StringBuilder append(boolean b) { return this; }
    public String toString() { return null; }
}
