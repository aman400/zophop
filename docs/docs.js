// ZopHop Interactive Documentation UI Script (App Theme Mode + 6 Palettes + Search + Lightbox)
document.addEventListener("DOMContentLoaded", () => {
  const version = typeof APP_VERSION !== "undefined" ? APP_VERSION : "3.0.0";

  // 0. Inject subtle theme-aware background sprites layer across all pages
  if (!document.querySelector(".bg-sprites")) {
    const bgLayer = document.createElement("div");
    bgLayer.className = "bg-sprites";
    bgLayer.setAttribute("aria-hidden", "true");
    bgLayer.innerHTML = `
      <div class="bg-sprite bg-sprite--bolt">
        <svg viewBox="0 0 120 120" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">
          <path d="M76 22 L44 62 L72 62 L40 98"/>
          <circle cx="76" cy="22" r="9" fill="currentColor" fill-opacity="0.22"/>
          <circle cx="40" cy="98" r="9" fill="currentColor" fill-opacity="0.22"/>
        </svg>
      </div>
      <div class="bg-sprite bg-sprite--accent bg-sprite--sms">
        <svg viewBox="0 0 120 120" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M22 30 h76 a12 12 0 0 1 12 12 v40 a12 12 0 0 1 -12 12 H52 l-18 14 v-14 H22 a12 12 0 0 1 -12 -12 V42 a12 12 0 0 1 12 -12 z"/>
          <circle cx="42" cy="62" r="3.5" fill="currentColor"/>
          <circle cx="60" cy="62" r="3.5" fill="currentColor"/>
          <circle cx="78" cy="62" r="3.5" fill="currentColor"/>
        </svg>
      </div>
      <div class="bg-sprite bg-sprite--accent bg-sprite--cron">
        <svg viewBox="0 0 100 100" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="50" cy="54" r="32" stroke-dasharray="6 5"/>
          <path d="M50 34 v20 l14 8"/>
          <path d="M38 14 h24"/>
        </svg>
      </div>
      <div class="bg-sprite bg-sprite--graph">
        <svg viewBox="0 0 140 120" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M30 60 C55 60 55 28 84 28"/>
          <path d="M30 60 C55 60 55 92 84 92"/>
          <path d="M98 28 L118 60 L98 92"/>
          <rect x="14" y="48" width="24" height="24" rx="7" fill="currentColor" fill-opacity="0.16"/>
          <circle cx="92" cy="28" r="10" fill="currentColor" fill-opacity="0.16"/>
          <circle cx="92" cy="92" r="10" fill="currentColor" fill-opacity="0.16"/>
          <circle cx="120" cy="60" r="8" fill="currentColor" fill-opacity="0.24"/>
        </svg>
      </div>
      <div class="bg-sprite bg-sprite--webhook">
        <svg viewBox="0 0 120 120" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round">
          <path d="M40 28 c-10 0 -12 6 -12 14 v10 c0 6 -4 8 -8 8 c4 0 8 2 8 8 v10 c0 8 2 14 12 14"/>
          <path d="M80 28 c10 0 12 6 12 14 v10 c0 6 4 8 8 8 c-4 0 -8 2 -8 8 v10 c0 8 -2 14 -12 14"/>
          <circle cx="60" cy="60" r="6" fill="currentColor" fill-opacity="0.28"/>
          <path d="M49 48 a16 16 0 0 1 22 0" stroke-opacity="0.8"/>
          <path d="M49 72 a16 16 0 0 0 22 0" stroke-opacity="0.8"/>
        </svg>
      </div>
      <div class="bg-sprite bg-sprite--accent bg-sprite--pulse">
        <svg viewBox="0 0 100 100" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M50 18 c-14 0 -22 10 -22 24 v14 l-6 10 h56 l-6 -10 V42 c0 -14 -8 -24 -22 -24 z"/>
          <path d="M42 74 a8 8 0 0 0 16 0"/>
          <path d="M18 36 a38 38 0 0 1 8 -18" stroke-dasharray="4 4"/>
          <path d="M82 36 a38 38 0 0 0 -8 -18" stroke-dasharray="4 4"/>
        </svg>
      </div>
      <div class="bg-sprite bg-sprite--vault">
        <svg viewBox="0 0 120 120" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round">
          <path d="M60 16 L96 30 v28 c0 24 -15 39 -36 46 C39 97 24 82 24 58 V30 L60 16 z"/>
          <circle cx="60" cy="54" r="8" fill="currentColor" fill-opacity="0.22"/>
          <path d="M60 62 v14"/>
        </svg>
      </div>
      <div class="bg-sprite bg-sprite--accent bg-sprite--plugin">
        <svg viewBox="0 0 120 120" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M36 28 h20 a8 8 0 0 1 16 0 h16 v20 a8 8 0 0 1 0 16 v24 H36 V64 a8 8 0 0 0 0 -16 V28 z"/>
          <circle cx="62" cy="58" r="10" stroke-dasharray="3 3"/>
        </svg>
      </div>
    `;
    document.body.prepend(bgLayer);
  }

  // 1. Sync version placeholders across any page
  document.querySelectorAll(".app-version").forEach(el => (el.textContent = version));
  document.querySelectorAll(".app-version-v").forEach(el => (el.textContent = "v" + version));
  document.querySelectorAll(".app-apk-name").forEach(el => (el.textContent = `ZopHop-v${version}.apk`));
  document.querySelectorAll("a.app-apk-link").forEach(el => {
    el.href = `https://github.com/aman400/zophop/releases/download/v${version}/ZopHop-v${version}.apk`;
  });

  // 2. Theme Mode (Dark / Light) + Palette Switcher matching AppThemePalette
  const APP_PALETTES = [
    { id: "emerald", name: "Emerald & Amber", sub: "ZopHop classic green & gold", primary: "#059669", secondary: "#d97706", surface: "#ecfdf5" },
    { id: "ocean", name: "Ocean & Teal", sub: "Crisp sky blue & coastal teal", primary: "#0284c7", secondary: "#0d9488", surface: "#e0f2fe" },
    { id: "indigo", name: "Indigo & Violet", sub: "Electric indigo & soft plum", primary: "#4f46e5", secondary: "#9333ea", surface: "#e0e7ff" },
    { id: "crimson", name: "Crimson & Coral", sub: "Bold ruby & warm sunset", primary: "#dc2626", secondary: "#ea580c", surface: "#fee2e2" },
    { id: "forest", name: "Forest & Citrus", sub: "Deep pine teal & fresh lime", primary: "#0f766e", secondary: "#65a30d", surface: "#ccfbf1" },
    { id: "slate", name: "Slate & Steel", sub: "Minimal monochrome charcoal", primary: "#334155", secondary: "#64748b", surface: "#e2e8f0" },
  ];

  const savedTheme = localStorage.getItem("zophop_docs_theme") || "dark";
  const savedPalette = localStorage.getItem("zophop_docs_palette") || "emerald";
  document.documentElement.setAttribute("data-theme", savedTheme);
  document.documentElement.setAttribute("data-palette", savedPalette);

  const themeToggle = document.getElementById("themeToggle");
  if (themeToggle) {
    themeToggle.textContent = savedTheme === "light" ? "🌙" : "☀️";
    themeToggle.addEventListener("click", () => {
      const current = document.documentElement.getAttribute("data-theme") || "dark";
      const next = current === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", next);
      localStorage.setItem("zophop_docs_theme", next);
      themeToggle.textContent = next === "light" ? "🌙" : "☀️";
    });
  }

  // Inject Palette Picker next to #themeToggle in .nav-actions
  const navActions = document.querySelector(".nav-actions");
  if (navActions) {
    const wrap = document.createElement("div");
    wrap.className = "palette-wrap";

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "palette-btn";
    btn.title = "Switch ZopHop App Color Palette";

    const dots = document.createElement("span");
    dots.className = "palette-dots";

    const label = document.createElement("span");
    label.className = "palette-btn-label";
    label.textContent = "Palette";

    btn.appendChild(dots);
    btn.appendChild(label);

    const menu = document.createElement("div");
    menu.className = "palette-menu";

    const menuTitle = document.createElement("div");
    menuTitle.className = "palette-menu-header";
    menuTitle.textContent = "App Theme Palette";
    menu.appendChild(menuTitle);

    const updatePaletteUI = activeId => {
      const active = APP_PALETTES.find(p => p.id === activeId) || APP_PALETTES[0];
      dots.innerHTML = "";
      [active.primary, active.secondary, active.surface].forEach(hex => {
        const dot = document.createElement("span");
        dot.className = "palette-dot";
        dot.style.backgroundColor = hex;
        dots.appendChild(dot);
      });
      menu.querySelectorAll(".palette-option").forEach(opt => {
        opt.classList.toggle("active", opt.dataset.palette === active.id);
      });
    };

    APP_PALETTES.forEach(p => {
      const opt = document.createElement("button");
      opt.type = "button";
      opt.className = "palette-option";
      opt.dataset.palette = p.id;

      const meta = document.createElement("span");
      meta.className = "palette-option-meta";
      const nameSpan = document.createElement("span");
      nameSpan.className = "palette-option-name";
      nameSpan.textContent = p.name;
      const subSpan = document.createElement("span");
      subSpan.className = "palette-option-sub";
      subSpan.textContent = p.sub;
      meta.appendChild(nameSpan);
      meta.appendChild(subSpan);

      const preview = document.createElement("span");
      preview.className = "palette-dots";
      [p.primary, p.secondary, p.surface].forEach(hex => {
        const dot = document.createElement("span");
        dot.className = "palette-dot";
        dot.style.backgroundColor = hex;
        preview.appendChild(dot);
      });

      opt.appendChild(meta);
      opt.appendChild(preview);
      opt.addEventListener("click", () => {
        document.documentElement.setAttribute("data-palette", p.id);
        localStorage.setItem("zophop_docs_palette", p.id);
        updatePaletteUI(p.id);
        menu.classList.remove("open");
      });
      menu.appendChild(opt);
    });

    updatePaletteUI(savedPalette);
    btn.addEventListener("click", e => {
      e.stopPropagation();
      menu.classList.toggle("open");
    });
    document.addEventListener("click", () => menu.classList.remove("open"));
    menu.addEventListener("click", e => e.stopPropagation());

    wrap.appendChild(btn);
    wrap.appendChild(menu);
    if (themeToggle) {
      navActions.insertBefore(wrap, themeToggle);
    } else {
      navActions.appendChild(wrap);
    }
  }

  // 3. Mobile Sidebar Drawer Toggle
  const sidebarToggle = document.getElementById("sidebarToggle");
  const sidebar = document.getElementById("docsSidebar");
  if (sidebarToggle && sidebar) {
    sidebarToggle.addEventListener("click", () => {
      sidebar.classList.toggle("open");
    });
    sidebar.querySelectorAll("a").forEach(link => {
      link.addEventListener("click", () => sidebar.classList.remove("open"));
    });
  }

  // 4. Sidebar Section Search Filter
  const sidebarSearch = document.getElementById("sidebarSearch");
  if (sidebarSearch && sidebar) {
    const links = Array.from(sidebar.querySelectorAll(".sidebar-link"));
    sidebarSearch.addEventListener("input", () => {
      const q = sidebarSearch.value.trim().toLowerCase();
      links.forEach(link => {
        const text = (link.textContent + " " + (link.dataset.keywords || "")).toLowerCase();
        link.classList.toggle("hidden", q.length > 0 && !text.includes(q));
      });
    });
  }

  // 5. Scrollspy for Sidebar Links
  if (sidebar) {
    const sections = Array.from(document.querySelectorAll(".doc-section[id], .doc-hero[id]"));
    const navLinks = Array.from(sidebar.querySelectorAll(".sidebar-link[href^='#']"));
    const observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (entry.isIntersecting) {
            const id = entry.target.getAttribute("id");
            navLinks.forEach(l => l.classList.toggle("active", l.getAttribute("href") === `#${id}`));
          }
        });
      },
      { rootMargin: "-20% 0px -65% 0px", threshold: 0.01 }
    );
    sections.forEach(s => observer.observe(s));
  }

  // 6. Enhance .code-box with Header + Copy Button
  document.querySelectorAll(".code-box").forEach(box => {
    if (box.querySelector(".code-header")) return;
    const labelEl = box.querySelector(".code-label");
    const labelText = labelEl ? labelEl.textContent : "Code";
    if (labelEl) labelEl.remove();

    const rawCode = box.textContent.replace(/^\n+|\n+$/g, "");
    box.textContent = "";

    const header = document.createElement("div");
    header.className = "code-header";

    const labelSpan = document.createElement("span");
    labelSpan.className = "code-label";
    labelSpan.textContent = labelText;

    const copyBtn = document.createElement("button");
    copyBtn.type = "button";
    copyBtn.className = "copy-btn";
    copyBtn.textContent = "Copy";
    copyBtn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(rawCode);
        copyBtn.textContent = "Copied!";
        copyBtn.classList.add("copied");
        setTimeout(() => {
          copyBtn.textContent = "Copy";
          copyBtn.classList.remove("copied");
        }, 1600);
      } catch {
        copyBtn.textContent = "Failed";
      }
    });

    header.appendChild(labelSpan);
    header.appendChild(copyBtn);
    box.appendChild(header);
    box.appendChild(document.createTextNode(rawCode));
  });

  // 7. Scoped Tab Groups ([data-tab-group])
  document.querySelectorAll("[data-tab-group]").forEach(group => {
    const buttons = group.querySelectorAll(".tab-btn");
    const panels = group.querySelectorAll(".tab-panel");
    buttons.forEach(btn => {
      btn.addEventListener("click", () => {
        buttons.forEach(b => b.classList.remove("active"));
        panels.forEach(p => p.classList.remove("active"));
        btn.classList.add("active");
        const target = group.querySelector(`#${btn.dataset.tab}`);
        if (target) target.classList.add("active");
      });
    });
  });

  // 8. Gallery Category Filter
  const filterButtons = document.querySelectorAll(".filter-btn");
  const shotCards = document.querySelectorAll(".shot-card");
  filterButtons.forEach(btn => {
    btn.addEventListener("click", () => {
      filterButtons.forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
      const filter = btn.dataset.filter;
      shotCards.forEach(card => {
        const match = filter === "all" || card.dataset.category === filter;
        card.classList.toggle("hidden", !match);
      });
    });
  });

  // 9. Universal Lightbox Modal for all .shot-card elements
  const lightbox = document.getElementById("lightbox");
  const lightboxImg = document.getElementById("lightboxImg");
  const lightboxTitle = document.getElementById("lightboxTitle");
  const lightboxCaption = document.getElementById("lightboxCaption");
  const lightboxClose = document.getElementById("lightboxClose");

  if (lightbox && lightboxImg) {
    shotCards.forEach(card => {
      card.addEventListener("click", () => {
        const img = card.querySelector("img");
        if (!img) return;
        const caption = card.querySelector(".shot-caption")?.textContent || "";
        lightboxImg.src = img.src;
        lightboxImg.alt = img.alt;
        if (lightboxTitle) lightboxTitle.textContent = card.dataset.title || img.alt;
        if (lightboxCaption) lightboxCaption.textContent = caption;
        lightbox.classList.add("open");
        lightbox.setAttribute("aria-hidden", "false");
      });
    });

    const closeLightbox = () => {
      lightbox.classList.remove("open");
      lightbox.setAttribute("aria-hidden", "true");
    };
    if (lightboxClose) lightboxClose.addEventListener("click", closeLightbox);
    lightbox.addEventListener("click", e => {
      if (e.target === lightbox) closeLightbox();
    });
    document.addEventListener("keydown", e => {
      if (e.key === "Escape" && lightbox.classList.contains("open")) closeLightbox();
    });
  }
});
