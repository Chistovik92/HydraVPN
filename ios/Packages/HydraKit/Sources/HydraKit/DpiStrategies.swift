import Foundation

/// Стратегии обхода DPI для ByeDPI (`ciadpi`, MIT) и сайты для их проверки — тот же набор, что в Android (DpiStrategies.kt):
/// 60 стратегий из ByeByeDPI (github.com/romanvht/ByeByeDPI, GPL-3.0 — как и Hydra). `{sni}` подменяется на `fakeSni`.
public enum DpiStrategies {
    public static let fakeSni = "max.ru"
    /// Мягкая стратегия по умолчанию.
    public static let defaultStrategy = "-o 2 -d 2"

    public static let presets: [String] = [
        "-f-200 -Qr -s3:5+sm -a1 -As -d1 -s4+sm -s8+sh -f-300 -d6+sh -a1 -At,r,s -o2 -f-30 -As -r5 -Mh -r6+sh -f-250 -s2:7+s -s3:6+sm -a1 -At,r,s -s3:5+sm -s6+s -s7:9+s -q30+sm -a1",
        "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1",
        "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1",
        "-n {sni} -Qr -f-204 -s1:5+sm -a1 -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1",
        "-n {sni} -Qr -f-205 -a1 -As -s1:3+sm -a1 -As -s5:8+sm -a1 -As -d3 -q7 -o2 -f-43 -f-85 -f-165 -r5 -Mh -a1",
        "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -At,r,s -d1:11+sm -S -a1 -At,r,s -n {sni} -Qr -f1 -d1:11+sm -s1:11+sm -S -a1",
        "-d1 -s1 -q1 -a1 -Ar -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-f1+nme -t6 -a1 -As -n {sni} -Qr -s1:6+sm -a1 -As -s5:12+sm -a1 -As -d3 -q7 -r6 -Mh -a1",
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -Ar,s -o1 -a1 -At -r1+s -f-1 -t6 -a1",
        "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
        "-d1 -d3+s -s6+s -d6+s -s7+s -d8+s -s10+s -a1 -t12 -At,s -r3",
        "-f1 -t5 -n {sni} -q3+h -Qr -f2 -q1 -r1+s -t15 -q1 -o2 -a1",
        "-n {sni} -d2:5:2+h -f-3 -r2+sm -o2 -o50+s -r2+s -f-4 -a1",
        "-f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1 -As -r1+s -d8+s -a1",
        "-r-1+s -o20+sm -s3:7+sm -d5:3+sm -f300+s -Qr -f-1 -a1",
        "-o2 -O4 -s1 -q1 -a1 -Ar -s5 -o1+s -f1+s -r20+s -a1",
        "-o1 -r-5+se -a1 -At,r,s -d1 -n {sni} -Qr -f-1 -a1",
        "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
        "-n {sni} -Qr -f6+nr -d2 -d11 -f9+hm -o3 -t7 -a1",
        "-r5+s -s25+s -a1 -At,r,s -s50 -r5+s -s50+s -a1",
        "-d1 -d3+s -s6+s -d9+s -s20+s -d25+s -s30+s -a1",
        "-d9+s -q20+s -s25+s -t5 -a1 -At,r,s -r1+h -a1",
        "-q1+s -s29+s -s30+s -s14+s -o5+s -f-1 -S -a1",
        "-d1 -s1+s -r1+s -e1 -m1 -o1+s -f-1 -t2 -a1",
        "-d1 -o1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
        "-d1 -s4 -d8 -s1+s -d5+s -s10+s -d20+s -a1",
        "-f-1 -n {sni} -Qr -s2+s -r3 -o20 -t4 -a1",
        "-n {sni} -Qr -d5+sm -f3+sm -o2 -t4 -a1",
        "-o1 -a1 -Ar -q1 -a1 -At -f-1 -r1+s -a1",
        "-q1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
        "-s4+sn -r9+s -Qr -n {sni} -S -a1",
        "-o1 -d1 -r1+s -S -s1+s -d3+s -a1",
        "-q1+s -s29+s -o5+s -f-1 -S -a1",
        "-n {sni} -Qr -m2 -f-1 -d7 -a1",
        "-d1 -s1+s -r1+s -f-1 -t8 -a1",
        "-o1 -a1 -An -f1+nme -t6 -a1",
        "-n {sni} -Qr -f-1 -r1+s -a1",
        "-n {sni} -Qr -d1:3 -f-1 -a1",
        "-s1 -d3+s -a1 -At -r1+s -a1",
        "-f-1 -t8 -n {sni} -s1+s -a1",
        "-n {sni} -Qr -d1 -f-1 -a1",
        "-f64+se -n {sni} -t5 -a1",
        "-o1 -a1 -At,r,s -d1 -a1",
        "-d1+s -o2 -s5 -r5 -a1",
        "-r8 -o2 -s7 -q4+s -a1",
        "-o1 -f-1 -r-5+se -a1",
        "-d6+s -q4+hm -o2 -a1",
        "-s5+s -s35+s -m4 -a1",
        "-f-1+sm -t7 -m2 -a1",
        "-o1 -r-5+se -a1",
        "-o1+s -d3+s -a1",
        "-o1 -s4 -s6 -a1",
        "-q1 -r25+s -a1",
        "-d1 -s3+s -a1",
        "-o3 -d7 -a1",
        "-d7 -s2 -a1",
    ]

    /// Сайты проверки по группам.
    public static let sites: [String: [String]] = [
        "youtube": ["youtu.be", "youtube.com", "i.ytimg.com", "i9.ytimg.com", "yt3.ggpht.com", "yt4.ggpht.com", "googleapis.com", "jnn-pa.googleapis.com", "googleusercontent.com", "signaler-pa.youtube.com", "youtubei.googleapis.com", "manifest.googlevideo.com", "yt3.googleusercontent.com"],
        "discord": ["dis.gd", "discord.co", "discord.gg", "discord.app", "discord.com", "discord.dev", "discord.new", "discord.gift"],
        "telegram": ["telegram.org", "core.telegram.org", "web.telegram.org", "webk.telegram.org", "my.telegram.org", "translations.telegram.org", "instantview.telegram.org", "blog.telegram.org"],
        "general": ["rutracker.org", "nyaa.si", "rutor.org", "nnmclub.to", "speedtest.net", "ookla.com"],
        "cloudflare": ["cloudflare.net", "cloudflare.com", "cloudflarecn.net", "cloudflare-ech.com"],
        "googlevideo": ["rr1---sn-4axm-n8vs.googlevideo.com", "rr1---sn-gvnuxaxjvh-o8ge.googlevideo.com", "rr1---sn-ug5onuxaxjvh-p3ul.googlevideo.com", "rr1---sn-ug5onuxaxjvh-n8v6.googlevideo.com", "manifest.googlevideo.com"],
        "social": ["snapchat.com", "snap.com", "linkedin.com", "facebook.com", "fb.com", "instagram.com", "x.com", "twitter.com", "tiktok.com"],
        "turkiye": ["roblox.com", "wattpad.com", "pastebin.com", "4shared.com", "wikileaks.org"],
    ]

    /// Порядок встроенных списков в интерфейсе (как в ByeByeDPI - по алфавиту).
    public static let groupOrder = ["cloudflare", "discord", "general", "googlevideo", "social", "telegram", "turkiye", "youtube"]
}
