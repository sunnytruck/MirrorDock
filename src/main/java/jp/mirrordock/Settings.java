package jp.mirrordock;
public record Settings(String receiverName, String executable, boolean onTop, boolean audio,
                       boolean autoStart, boolean logging, double x, double y, double width, double height) {
    public static Settings defaults() { return new Settings("MirrorDock", "", false, true, false, false, 100, 100, 900, 660); }
    public Settings {
        if (receiverName == null || receiverName.isBlank() || receiverName.length() > 80 || receiverName.chars().anyMatch(Character::isISOControl)) receiverName = "MirrorDock";
        if (executable == null) executable = "";
        if (!Double.isFinite(x)) x = 100;
        if (!Double.isFinite(y)) y = 100;
        if (!Double.isFinite(width) || width < 640 || width > 10000) width = 900;
        if (!Double.isFinite(height) || height < 520 || height > 10000) height = 660;
    }
}
