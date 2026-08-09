package com.xinyv.median;

import android.net.Uri;

import java.util.List;

/** Fully local start page: zero startup network requests and no JavaScript bridge. */
final class HomePage {
    static String html(String selectedEngine, List<BrowserDataStore.Bookmark> bookmarks, boolean dark, String trustToken) {
        return html(selectedEngine, bookmarks, dark, trustToken, HomePageConfig.defaults());
    }

    static String html(String selectedEngine, List<BrowserDataStore.Bookmark> bookmarks, boolean dark,
                       String trustToken, HomePageConfig options) {
        if (options == null) options = HomePageConfig.defaults();
        String engine = "baidu".equals(selectedEngine) || "bing".equals(selectedEngine) || "custom".equals(selectedEngine) ? selectedEngine : "google";
        boolean wallpaper = options.hasWallpaper;
        String background = dark ? "#111315" : "#fff";
        String foreground = wallpaper ? "#fff" : (dark ? "#edf0f2" : "#202124");
        String secondary = wallpaper ? "rgba(255,255,255,.82)" : (dark ? "#aeb4ba" : "#5f6368");
        String surface = wallpaper ? "rgba(255,255,255,.20)" : (dark ? "#202327" : "#f1f3f4");
        String border = wallpaper ? "rgba(255,255,255,.28)" : (dark ? "#34383d" : "#dfe1e5");
        boolean glass = "glass".equals(options.searchStyle);
        String searchBackground = wallpaper && glass ? "rgba(18,20,24,.38)" :
                (wallpaper ? "rgba(255,255,255,.94)" : (glass ? (dark ? "rgba(42,45,50,.72)" : "rgba(255,255,255,.72)") : background));
        String searchText = wallpaper && !glass ? "#202124" : foreground;
        String searchMuted = wallpaper && !glass ? "#5f6368" : secondary;
        String tileRadius = "circle".equals(options.tileShape) ? "50%" : ("square".equals(options.tileShape) ? "8px" : "16px");
        StringBuilder shortcuts = new StringBuilder();
        int count = options.showShortcuts ? Math.min(12, bookmarks == null ? 0 : bookmarks.size()) : 0;
        if (options.showShortcuts) {
            for (int i = 0; i < count; i++) {
                BrowserDataStore.Bookmark item = bookmarks.get(i);
                String title = item.title == null || item.title.trim().length() == 0 ? host(item.url) : item.title.trim();
                if (title.length() > 18) title = title.substring(0, 18) + "…";
                String letter = title.length() == 0 ? "•" : title.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
                shortcuts.append("<a class='shortcut' href='median://open?url=")
                        .append(Uri.encode(item.url)).append("'><span class='tile'>")
                        .append(escape(letter)).append("</span><span class='label'>")
                        .append(escape(title)).append("</span></a>");
            }
            if (count == 0) shortcuts.append("<button class='empty' onclick=\"location.href='median://bookmarks'\">添加常用书签</button>");
        }

        StringBuilder page = new StringBuilder(9000 + shortcuts.length());
        page.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>")
                .append("<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; frame-src 'self'; connect-src 'none'; form-action 'none'; base-uri 'none'\">")
                .append("<meta name='median-home-token' content='").append(escape(trustToken)).append("'><meta name='color-scheme' content='")
                .append(dark ? "dark" : "light").append("'><style>")
                .append("*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}html,body{margin:0;min-height:100%;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;background:")
                .append(background).append(";color:").append(foreground).append("}body{display:flex;justify-content:center;overflow-x:hidden}")
                .append(".wall,.shade{position:fixed;z-index:0;top:0;right:0;bottom:0;left:0;pointer-events:none}.wall{background-position:center;background-repeat:no-repeat;background-size:")
                .append(options.wallpaperFit).append(";transform:scale(").append(options.wallpaperBlur > 0 ? "1.035" : "1").append(");filter:blur(").append(options.wallpaperBlur).append("px)}")
                .append(".shade{background:rgba(0,0,0,").append(options.wallpaperDim / 100.0f).append(")}.wrap{position:relative;z-index:1;width:100%;max-width:680px;padding:10vh 20px 44px;text-align:center;opacity:0;transform:translateY(10px);animation:enter .38s cubic-bezier(.2,0,0,1) forwards}")
                .append(".wrap.compact{padding-top:4.5vh}.brand{font-size:").append(options.logoFontSize).append("px;font-weight:").append(options.logoFontWeight).append(";letter-spacing:").append(options.logoLetterSpacing).append("px;white-space:pre-wrap;margin:0 0 10px;text-shadow:").append(wallpaper ? "0 2px 14px rgba(0,0,0,.45)" : "none").append("}.logo-gradient{display:inline-block;background:var(--logo-gradient);-webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent}.logo-space{display:inline-block;height:1px;letter-spacing:0}.logo{display:block;width:").append(options.logoImageWidth).append("px;height:").append(options.logoImageHeight).append("px;border-radius:").append(options.logoImageRadius).append("%;object-fit:contain;margin:0 auto;filter:drop-shadow(0 2px 12px rgba(0,0,0,.32))}.subtitle{min-height:20px;margin:0 0 18px;color:").append(secondary).append(";font-size:14px;text-shadow:").append(wallpaper ? "0 1px 6px rgba(0,0,0,.45)" : "none").append("}")
                .append(".clock{margin:0 0 16px;text-shadow:0 2px 12px rgba(0,0,0,.35)}.time{font-size:35px;font-weight:650;letter-spacing:-1px}.date{font-size:12px;color:").append(secondary).append(";margin-top:2px}")
                .append(".search{height:54px;transform:translateZ(0);border:1px solid ").append(border).append(";border-radius:28px;display:flex;align-items:center;padding:0 18px;background:").append(searchBackground).append(";box-shadow:0 2px 10px rgba(0,0,0,.16);transition:box-shadow .18s,transform .16s;-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px)}.search:focus-within{transform:scale(1.009);box-shadow:0 4px 16px rgba(0,0,0,.22)}.mag{width:15px;height:15px;border:2px solid ").append(searchMuted).append(";border-radius:50%;position:relative;flex:none;margin:0 15px 0 2px}.mag:after{content:'';position:absolute;width:7px;height:2px;background:").append(searchMuted).append(";right:-6px;bottom:-3px;transform:rotate(45deg);border-radius:2px}.search input{border:0;outline:0;font-size:17px;flex:1;min-width:0;background:transparent;color:").append(searchText).append("}.search input::placeholder{color:").append(searchMuted).append("}")
                .append(".engines{display:flex;justify-content:center;margin-top:15px;flex-wrap:wrap}.chip{border:0;background:transparent;border-radius:18px;padding:8px 14px;margin:3px;font-size:13px;color:").append(secondary).append("}.chip:active{transform:scale(.9)}.chip.active{background:").append(options.accentColor()).append(";font-weight:650;color:#fff}")
                .append(".shortcuts{display:grid;grid-template-columns:repeat(").append(options.shortcutColumns).append(",1fr);gap:16px 8px;margin:28px auto 0;max-width:440px}.shortcut{text-decoration:none;color:").append(foreground).append(";min-width:0;display:flex;flex-direction:column;align-items:center}.tile{display:grid;place-items:center;width:50px;height:50px;border-radius:").append(tileRadius).append(";background:").append(surface).append(";font-size:19px;font-weight:650;box-shadow:0 1px 5px rgba(0,0,0,.13);transition:transform .14s;-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px)}.shortcut:active .tile{transform:scale(.86)}.label{width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-top:7px;font-size:12px;text-shadow:").append(wallpaper ? "0 1px 5px rgba(0,0,0,.55)" : "none").append("}.empty{grid-column:1/-1;border:1px dashed ").append(border).append(";background:transparent;color:").append(secondary).append(";border-radius:16px;padding:16px}.corner{position:fixed;z-index:2;top:16px;left:18px;font-size:13px;font-weight:650;color:").append(secondary).append(";text-shadow:").append(wallpaper ? "0 1px 6px rgba(0,0,0,.55)" : "none").append("}")
                .append(".custom-home{position:fixed;z-index:1;inset:0;width:100%;height:100%;border:0;background:transparent}")
                .append("@keyframes enter{to{opacity:1;transform:none}}@media(prefers-reduced-motion:reduce){.wrap{animation:none;opacity:1;transform:none}.search,.tile{transition:none}}@media(max-height:600px){.wrap{padding-top:5vh}.wrap.compact{padding-top:2vh}.shortcuts{margin-top:18px}}")
                .append(options.customCss).append("</style></head><body>");
        if (wallpaper) page.append("<div class='wall' style=\"background-image:url('/home-wallpaper?v=").append(options.wallpaperVersion).append("')\"></div><div class='shade'></div>");
        if (options.customHtmlEnabled) {
            page.append("<iframe class='custom-home' title='自定义主页' sandbox='allow-scripts allow-forms allow-popups allow-popups-to-escape-sandbox allow-top-navigation-by-user-activation' src='/home-custom?v=")
                    .append(options.customHtmlVersion).append("'></iframe></body></html>");
            return page.toString();
        }
        if (options.showCornerBrand) page.append("<div class='corner'>").append(escape(options.title)).append("</div>");
        page.append("<main class='wrap ").append(escape(options.layout)).append("'>");
        if (options.showClock) page.append("<div class='clock'><div class='time' id='clock'>--:--</div><div class='date' id='date'></div></div>");
        if ("image".equals(options.logoMode) && options.hasLogo)
            page.append("<div class='brand'><img class='logo' src='/home-logo?v=").append(options.logoVersion).append("' alt=''></div>");
        else if (!"none".equals(options.logoMode))
            page.append("<div class='brand' aria-label='").append(escape(options.title)).append("'>")
                    .append(LogoMarkup.renderPreset(options.logoStyle, options.title, options.logoCode,
                            options.logoGradientAngle)).append("</div>");
        if (options.subtitle.length() > 0) page.append("<div class='subtitle'>").append(escape(options.subtitle)).append("</div>");
        else page.append("<div class='subtitle'></div>");
        if (options.showSearch) {
            page.append("<form id='form'><div class='search'><span class='mag'></span><input id='q' autocomplete='off' enterkeyhint='search' placeholder='搜索或输入网址'></div></form>");
            if (options.showEngines) page.append("<div class='engines'><button class='chip' data-e='google'>Google</button><button class='chip' data-e='baidu'>百度</button><button class='chip' data-e='bing'>Bing</button><button class='chip' data-e='custom'>自定义</button></div>");
        }
        if (options.showShortcuts) page.append("<div class='shortcuts'>").append(shortcuts).append("</div>");
        page.append("</main><script>let e='").append(engine).append("';function draw(){document.querySelectorAll('.chip').forEach(x=>x.classList.toggle('active',x.dataset.e===e))}document.querySelectorAll('.chip').forEach(x=>x.onclick=function(ev){ev.preventDefault();e=this.dataset.e;draw();location.href='median://engine?name='+e});let f=document.getElementById('form');if(f)f.onsubmit=function(ev){ev.preventDefault();let q=document.getElementById('q').value.trim();if(q)location.href='median://search?engine='+e+'&q='+encodeURIComponent(q)};let c=document.getElementById('clock');if(c){function tick(){let d=new Date();c.textContent=d.getHours()+':'+('0'+d.getMinutes()).slice(-2);document.getElementById('date').textContent=d.toLocaleDateString(undefined,{month:'long',day:'numeric',weekday:'short'})}tick();setInterval(tick,30000)}draw()</script></body></html>");
        return page.toString();
    }

    private static String host(String url) {
        try {
            String value = Uri.parse(url).getHost();
            if (value == null) return "书签";
            return value.startsWith("www.") ? value.substring(4) : value;
        } catch (Exception ignored) { return "书签"; }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private HomePage() {}
}
