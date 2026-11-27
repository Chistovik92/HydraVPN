package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.desktop.ui.FlagImages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.8.4-rc1: флаги на ПК — PNG Twemoji по ISO-коду (Windows не рендерит флаги-эмодзи).
 * Страж: каждый код, который `ServerLocation.isoCode` может вернуть для реальной страны,
 * имеет картинку в `resources/flags` — иначе в строке сервера не будет флага.
 */
class FlagImagesTest {
    /** ISO 3166-1 alpha-2 (249 назначенных) + «xk»/«eu», которые панели тоже ставят в имена серверов. */
    private val iso =
        ("ad ae af ag ai al am ao aq ar as at au aw ax az " +
        "ba bb bd be bf bg bh bi bj bl bm bn bo bq br bs bt bv bw by bz " +
        "ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz " +
        "de dj dk dm do dz " +
        "ec ee eg eh er es et eu " +
        "fi fj fk fm fo fr " +
        "ga gb gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy " +
        "hk hm hn hr ht hu " +
        "id ie il im in io iq ir is it " +
        "je jm jo jp " +
        "ke kg kh ki km kn kp kr kw ky kz " +
        "la lb lc li lk lr ls lt lu lv ly " +
        "ma mc md me mf mg mh mk ml mm mn mo mp mq mr ms mt mu mv mw mx my mz " +
        "na nc ne nf ng ni nl no np nr nu nz " +
        "om " +
        "pa pe pf pg ph pk pl pm pn pr ps pt pw py " +
        "qa " +
        "re ro rs ru rw " +
        "sa sb sc sd se sg sh si sj sk sl sm sn so sr ss st sv sx sy sz " +
        "tc td tf tg th tj tk tl tm tn to tr tt tv tw tz " +
        "ua ug um us uy uz " +
        "va vc ve vg vi vn vu " +
        "wf ws xk " +
        "ye yt " +
        "za zm zw").split(" ")

    @Test fun everyIsoCodeHasBundledFlag() {
        assertEquals(251, iso.size)
        for (c in iso) assertNotNull(FlagImages.image(c), "нет флага для '$c'")
    }

    @Test fun unknownCodeHasNoImage() {
        assertNull(FlagImages.image("zz"))
        assertNull(FlagImages.image("q"))
    }

    @Test fun flagDecodesToTwemoji72Px() {
        assertEquals(72, FlagImages.image("de")!!.width)
        assertTrue(FlagImages.image("ru")!!.height == 72)
    }
}
