(() => {
  const REPO = "Carloj005/aw-android";
  const FALLBACK_RELEASE = {
    tag_name: "v1.4.4",
    apk_url: "https://github.com/Carloj005/aw-android/releases/download/v1.4.4/AW-Anime-v1.4.4.apk",
    sha_url: "https://github.com/Carloj005/aw-android/releases/download/v1.4.4/AW-Anime-v1.4.4.apk.sha256",
    size_mb: "2.8 MB",
    date: "Ultima release",
    notes: "Nuovo tasto rapido ✕ su Continua a guardare, fix anime in corso in pari (JoJo), fix anime interrotti Suite TV (Azur Lane) e fix conflitto Yu-Gi-Oh!.",
  };

  // 1. Theme toggle (Dark / Light Material You)
  const root = document.documentElement;
  const themeBtn = document.getElementById("theme-toggle");
  const savedTheme = localStorage.getItem("aw_web_theme");
  const prefersLight = window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches;
  const initialTheme = savedTheme || (prefersLight ? "light" : "dark");
  root.setAttribute("data-theme", initialTheme);
  updateThemeIcon(initialTheme);

  function updateThemeIcon(theme) {
    if (!themeBtn) return;
    themeBtn.innerHTML = theme === "dark" ? "☀️" : "🌙";
    themeBtn.title = theme === "dark" ? "Passa al tema chiaro" : "Passa al tema scuro";
    themeBtn.setAttribute("aria-label", themeBtn.title);
  }

  if (themeBtn) {
    themeBtn.addEventListener("click", () => {
      const next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      localStorage.setItem("aw_web_theme", next);
      updateThemeIcon(next);
    });
  }

  // 2. Scroll reveal animations
  const revealEls = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("visible");
            io.unobserve(entry.target);
          }
        }
      },
      { threshold: 0.12 }
    );
    revealEls.forEach((el) => io.observe(el));
  } else {
    revealEls.forEach((el) => el.classList.add("visible"));
  }

  // 3. Apply release data to all download buttons and metadata
  function applyReleaseData(data) {
    document.querySelectorAll("[data-apk-download]").forEach((el) => {
      el.href = data.apk_url;
    });
    document.querySelectorAll("[data-version-badge]").forEach((el) => {
      el.textContent = data.tag_name;
    });
    document.querySelectorAll("[data-apk-size]").forEach((el) => {
      el.textContent = data.size_mb;
    });
    document.querySelectorAll("[data-release-date]").forEach((el) => {
      el.textContent = data.date;
    });
    const notesEl = document.getElementById("release-notes");
    if (notesEl && data.notes) {
      notesEl.textContent = data.notes;
    }
    const shaLink = document.getElementById("sha-link");
    if (shaLink && data.sha_url) {
      shaLink.href = data.sha_url;
    }
  }

  function formatMb(bytes) {
    if (!bytes || bytes <= 0) return FALLBACK_RELEASE.size_mb;
    return (bytes / (1024 * 1024)).toFixed(2) + " MB";
  }

  function formatItalianDate(iso) {
    if (!iso) return FALLBACK_RELEASE.date;
    try {
      return new Intl.DateTimeFormat("it-IT", {
        day: "numeric",
        month: "long",
        year: "numeric",
      }).format(new Date(iso));
    } catch {
      return FALLBACK_RELEASE.date;
    }
  }

  applyReleaseData(FALLBACK_RELEASE);

  // Fetch live latest release from GitHub API
  fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
    headers: { Accept: "application/vnd.github+json" },
  })
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error("HTTP " + r.status))))
    .then((rel) => {
      const assets = Array.isArray(rel.assets) ? rel.assets : [];
      const apk = assets.find((a) => String(a.name || "").toLowerCase().endsWith(".apk"));
      const sha = assets.find((a) => String(a.name || "").toLowerCase().endsWith(".sha256"));
      if (!apk || !apk.browser_download_url) return;

      const cleanNotes = String(rel.body || "")
        .replace(/##\s*/g, "")
        .replace(/\*\*/g, "")
        .trim();

      applyReleaseData({
        tag_name: rel.tag_name || FALLBACK_RELEASE.tag_name,
        apk_url: apk.browser_download_url,
        sha_url: sha ? sha.browser_download_url : FALLBACK_RELEASE.sha_url,
        size_mb: formatMb(apk.size),
        date: formatItalianDate(rel.published_at),
        notes: cleanNotes || FALLBACK_RELEASE.notes,
      });

      const shaBox = document.getElementById("sha-value");
      if (shaBox && sha && sha.browser_download_url) {
        shaBox.textContent = `Verifica automatica attiva su ${apk.name} (file .sha256 allegato alla release ${rel.tag_name})`;
      }
    })
    .catch(() => {
      // Fallback already applied gracefully
    });

  // 4. Download button friendly hint toast
  const toast = document.getElementById("download-toast");
  document.querySelectorAll("[data-apk-download]").forEach((btn) => {
    btn.addEventListener("click", () => {
      if (!toast) return;
      toast.hidden = false;
      setTimeout(() => {
        toast.hidden = true;
      }, 7000);
    });
  });
})();
