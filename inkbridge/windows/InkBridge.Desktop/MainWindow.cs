using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using InkBridge.Model;
using InkBridge.Net;
using InkBridge.Views;

namespace InkBridge;

public sealed class MainWindow : Window
{
    private readonly Session _session = new();
    private readonly LinkServer _server = new();
    private readonly PageCanvas _canvas = new();
    private readonly TextBlock _title = new() { FontSize = 18, FontWeight = FontWeight.Bold, VerticalAlignment = VerticalAlignment.Center, MaxWidth = 320, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _pageLabel = new() { VerticalAlignment = VerticalAlignment.Center, MinWidth = 110, TextAlignment = TextAlignment.Center, FontSize = 15 };
    private readonly CheckBox _follow = new() { Content = "Seguir o tablet", IsChecked = true, VerticalAlignment = VerticalAlignment.Center };
    private readonly ToggleButton _fitWidth = new() { Content = "Ajustar à largura" };
    private readonly ToggleButton _topmost = new() { Content = "Sempre visível" };
    private readonly TextBlock _status = new() { VerticalAlignment = VerticalAlignment.Center, FontSize = 14 };
    private readonly TextBlock _usbInfo = new() { VerticalAlignment = VerticalAlignment.Center, FontSize = 13, Foreground = Brushes.DimGray, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly Ellipse _dot = new() { Width = 12, Height = 12, VerticalAlignment = VerticalAlignment.Center };
    private readonly Border _placeholder;
    private int _viewIndex;
    private bool _connected;
    private string? _connectedVia;

    private static string SaveDir
    {
        get
        {
            var docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
            if (string.IsNullOrEmpty(docs))
                docs = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Documents");
            return System.IO.Path.Combine(docs, "InkBridge");
        }
    }

    public MainWindow(string[] args)
    {
        Title = "InkBridge — mesa de notas";
        Width = 1280; Height = 860;
        MinWidth = 640; MinHeight = 420;
        Background = Brushes.White;
        try { Icon = new WindowIcon(Avalonia.Platform.AssetLoader.Open(new Uri("avares://InkBridge/Assets/inkbridge.ico"))); } catch { }

        // ---- barra de cima
        var prev = Btn("◀", () => ShowPage(_viewIndex - 1, userAction: true));
        var next = Btn("▶", () => ShowPage(_viewIndex + 1, userAction: true));
        _follow.IsCheckedChanged += (_, _) => { if (_follow.IsChecked == true) FollowTablet(); };
        _fitWidth.IsCheckedChanged += (_, _) => { _canvas.FitWidth = _fitWidth.IsChecked == true; _canvas.ResetView(); };
        _topmost.IsCheckedChanged += (_, _) => Topmost = _topmost.IsChecked == true;

        var top = new WrapPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 6) };
        top.Children.Add(_title);
        top.Children.Add(Sep());
        top.Children.Add(prev);
        top.Children.Add(_pageLabel);
        top.Children.Add(next);
        top.Children.Add(Sep());
        top.Children.Add(_follow);
        top.Children.Add(Sep());
        top.Children.Add(_fitWidth);
        top.Children.Add(Btn("Exportar PNG", () => _ = ExportPng()));
        top.Children.Add(Btn("Abrir…", () => _ = OpenFile()));
        top.Children.Add(Btn("Pasta", OpenSaveFolder));
        top.Children.Add(Sep());
        top.Children.Add(_topmost);
        top.Children.Add(Btn("Ecrã inteiro", ToggleFullScreen));
        foreach (var c in top.Children.OfType<Control>()) c.Margin = new Thickness(3, 2);

        // ---- barra de baixo (estado da ligação)
        var bottom = new DockPanel { Margin = new Thickness(12, 6), LastChildFill = true };
        var usbBtn = Btn("Ligar por USB", () => _ = ReverseUsb(onlyNew: false));
        DockPanel.SetDock(usbBtn, Dock.Right);
        bottom.Children.Add(usbBtn);
        var statusRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        statusRow.Children.Add(_dot);
        statusRow.Children.Add(_status);
        statusRow.Children.Add(new TextBlock { Text = "  ", VerticalAlignment = VerticalAlignment.Center });
        statusRow.Children.Add(_usbInfo);
        bottom.Children.Add(statusRow);

        // ---- centro
        _placeholder = new Border
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Background = Brushes.White,
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(32, 24),
            BorderBrush = new SolidColorBrush(Color.FromRgb(0xE5, 0xE7, 0xEB)),
            BorderThickness = new Thickness(1),
            Child = new TextBlock
            {
                FontSize = 16,
                LineHeight = 26,
                TextWrapping = TextWrapping.Wrap,
                MaxWidth = 560,
                Text = "À espera do tablet.\n\n" +
                       "Wi-Fi: tablet e PC na mesma rede. No tablet, abra um caderno, toque no ícone de ligação " +
                       "e escolha este PC (" + Environment.MachineName + ").\n\n" +
                       "USB: ligue o cabo, ative a «Depuração USB» no tablet, carregue em «Ligar por USB» aqui " +
                       "e no tablet escolha «Cabo USB».",
            },
        };
        var center = new Panel();
        center.Children.Add(_canvas);
        center.Children.Add(_placeholder);

        var root = new DockPanel();
        var topBorder = new Border { Child = top, Background = new SolidColorBrush(Color.FromRgb(0xF9, 0xFA, 0xFB)), BorderBrush = new SolidColorBrush(Color.FromRgb(0xD1, 0xD5, 0xDB)), BorderThickness = new Thickness(0, 0, 0, 1) };
        var bottomBorder = new Border { Child = bottom, Background = new SolidColorBrush(Color.FromRgb(0xF9, 0xFA, 0xFB)), BorderBrush = new SolidColorBrush(Color.FromRgb(0xD1, 0xD5, 0xDB)), BorderThickness = new Thickness(0, 1, 0, 0) };
        DockPanel.SetDock(topBorder, Dock.Top);
        DockPanel.SetDock(bottomBorder, Dock.Bottom);
        root.Children.Add(topBorder);
        root.Children.Add(bottomBorder);
        root.Children.Add(center);
        Content = root;

        // ---- ligação
        _session.StructureChanged += OnStructureChanged;
        _session.ContentChanged += page => { if (page == _canvas.Page) _canvas.InvalidateVisual(); };
        _session.HoverChanged += () => { _canvas.Hover = _session.Hover; if (IsShowingTabletPage()) _canvas.InvalidateVisual(); };
        _server.LineReceived += line =>
        {
            var apply = _session.Prepare(line);
            if (apply != null) Dispatcher.UIThread.Post(apply);
        };
        _server.ClientChanged += (device, endpoint) => Dispatcher.UIThread.Post(() =>
        {
            _connected = device != null;
            _session.Device = device;
            _connectedVia = endpoint == null ? null : endpoint == "127.0.0.1" ? "USB" : "Wi-Fi " + endpoint;
            if (!_connected) _session.Disconnected();
            UpdateStatus();
        });
        _server.Start();

        // guarda o caderno recebido a cada 2 s (se mudou)
        var saveTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        saveTimer.Tick += (_, _) => SaveIfDirty();
        saveTimer.Start();

        // USB automático: se houver adb e um tablet ligado, faz o "adb reverse" sozinho
        var usbTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        usbTimer.Tick += (_, _) => { if (!_connected) _ = ReverseUsb(onlyNew: true); };
        usbTimer.Start();

        KeyDown += OnKey;
        Closing += (_, _) => { SaveIfDirty(); _server.Dispose(); };

        UpdateStatus();
        UpdatePageLabel();
        HandleArgs(args);
    }

    // ------------------------------------------------------------------ interface

    private static Button Btn(string text, Action onClick)
    {
        var b = new Button { Content = text, VerticalAlignment = VerticalAlignment.Center };
        b.Click += (_, _) => onClick();
        return b;
    }

    private static Control Sep() => new Border
    {
        Width = 1, Height = 24, Margin = new Thickness(6, 0),
        Background = new SolidColorBrush(Color.FromRgb(0xD1, 0xD5, 0xDB)),
        VerticalAlignment = VerticalAlignment.Center,
    };

    private void UpdateStatus()
    {
        if (_server.Error != null)
        {
            _dot.Fill = Brushes.Red;
            _status.Text = _server.Error;
            return;
        }
        if (_connected)
        {
            _dot.Fill = new SolidColorBrush(Color.FromRgb(0x16, 0xA3, 0x4A));
            _status.Text = $"Ligado: {_session.Device} ({_connectedVia})";
        }
        else
        {
            _dot.Fill = new SolidColorBrush(Color.FromRgb(0xF5, 0x9E, 0x0B));
            var ips = LinkServer.LocalAddresses();
            _status.Text = "À espera do tablet — este PC: " + Environment.MachineName +
                           (ips.Count > 0 ? "  ·  IP " + string.Join(", ", ips) : "") + $"  ·  porta {LinkServer.TcpPort}";
        }
        _placeholder.IsVisible = _session.Notebook == null;
    }

    private void UpdatePageLabel()
    {
        var nb = _session.Notebook;
        _title.Text = nb?.Title ?? "InkBridge";
        _pageLabel.Text = nb == null ? "—" : $"Página {_viewIndex + 1} / {nb.Pages.Count}";
        Title = nb == null ? "InkBridge — mesa de notas" : $"{nb.Title} — InkBridge";
    }

    private bool IsShowingTabletPage() =>
        _session.Notebook is { } nb && _viewIndex == nb.Current;

    private void OnStructureChanged()
    {
        var nb = _session.Notebook;
        if (nb == null) return;
        if (_follow.IsChecked == true) FollowTablet();
        else ShowPage(Math.Clamp(_viewIndex, 0, nb.Pages.Count - 1), userAction: false);
        UpdateStatus();
    }

    private void FollowTablet()
    {
        if (_session.Notebook is { } nb) ShowPage(nb.Current, userAction: false);
    }

    private void ShowPage(int index, bool userAction)
    {
        var nb = _session.Notebook;
        if (nb == null) return;
        index = Math.Clamp(index, 0, nb.Pages.Count - 1);
        var page = nb.Pages[index];
        bool changed = page != _canvas.Page;
        // ao mudar de página à mão, deixa de seguir o tablet
        if (userAction && index != nb.Current) _follow.IsChecked = false;
        _viewIndex = index;
        _canvas.Hover = IsShowingTabletPage() ? _session.Hover : null;
        _canvas.SetPage(page, resetView: changed && (_canvas.Page == null || page.W != _canvas.Page.W || page.H != _canvas.Page.H));
        UpdatePageLabel();
    }

    private void OnKey(object? sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.F11: ToggleFullScreen(); break;
            case Key.Escape when WindowState == WindowState.FullScreen: WindowState = WindowState.Normal; break;
            case Key.Left or Key.PageUp: ShowPage(_viewIndex - 1, userAction: true); break;
            case Key.Right or Key.PageDown: ShowPage(_viewIndex + 1, userAction: true); break;
            case Key.F: _follow.IsChecked = true; break;
            default: return;
        }
        e.Handled = true;
    }

    private void ToggleFullScreen() =>
        WindowState = WindowState == WindowState.FullScreen ? WindowState.Normal : WindowState.FullScreen;

    // ------------------------------------------------------------------ USB

    private bool _usbBusy;

    private async Task ReverseUsb(bool onlyNew)
    {
        if (_usbBusy) return;
        _usbBusy = true;
        try
        {
            if (onlyNew && Adb.Find() == null) return; // sem adb: o modo automático fica calado
            var msg = await Adb.ReverseAllAsync(onlyNew);
            if (!onlyNew || msg.StartsWith("USB pronto") || msg.StartsWith("Falhou")) _usbInfo.Text = msg.Replace('\n', ' ');
            if (!onlyNew && msg.StartsWith("adb não encontrado"))
                await Info("USB", msg + "\n\nTransfira «SDK Platform-Tools for Windows» do site oficial do Android (developer.android.com), " +
                                  "descompacte e copie a pasta «platform-tools» para a pasta onde está o InkBridge.exe.");
        }
        finally { _usbBusy = false; }
    }

    // ------------------------------------------------------------------ ficheiros

    private void SaveIfDirty()
    {
        var nb = _session.Notebook;
        if (nb == null || !_session.Dirty) return;
        _session.Dirty = false;
        string text;
        try { text = NotebookJson.Serialize(nb); }
        catch { return; }
        var name = string.Concat(nb.Title.Select(ch => System.IO.Path.GetInvalidFileNameChars().Contains(ch) ? '_' : ch));
        var file = System.IO.Path.Combine(SaveDir, $"{name} ({nb.Id}).json");
        Task.Run(() =>
        {
            try
            {
                Directory.CreateDirectory(SaveDir);
                var tmp = file + ".tmp";
                File.WriteAllText(tmp, text);
                File.Move(tmp, file, overwrite: true);
            }
            catch { /* tenta outra vez na próxima alteração */ }
        });
    }

    private void OpenSaveFolder()
    {
        Directory.CreateDirectory(SaveDir);
        try
        {
            if (OperatingSystem.IsWindows()) Process.Start("explorer.exe", SaveDir);
            else Process.Start(new ProcessStartInfo("xdg-open", SaveDir) { UseShellExecute = false });
        }
        catch { }
    }

    private void LoadFile(string path)
    {
        using var doc = System.Text.Json.JsonDocument.Parse(File.ReadAllText(path));
        var nb = NotebookJson.ParseNotebook(doc.RootElement);
        _session.SetNotebook(nb);
        _session.Dirty = false;
    }

    private async Task OpenFile()
    {
        Directory.CreateDirectory(SaveDir);
        var start = await StorageProvider.TryGetFolderFromPathAsync(SaveDir);
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "Abrir caderno guardado",
            AllowMultiple = false,
            SuggestedStartLocation = start,
            FileTypeFilter = [new FilePickerFileType("Caderno InkBridge") { Patterns = ["*.json"] }],
        });
        var f = files.FirstOrDefault()?.TryGetLocalPath();
        if (f == null) return;
        try { LoadFile(f); }
        catch (Exception e) { await Info("Erro", "Não foi possível abrir: " + e.Message); }
    }

    public static void RenderPagePng(Page p, Stream output, double scale = 2)
    {
        var size = new PixelSize((int)Math.Ceiling(p.W * scale), (int)Math.Ceiling(p.H * scale));
        using var rtb = new RenderTargetBitmap(size, new Vector(96, 96));
        using (var ctx = rtb.CreateDrawingContext())
        using (ctx.PushTransform(Matrix.CreateScale(scale, scale)))
        {
            PageRenderer.DrawPage(ctx, p);
        }
        rtb.Save(output);
    }

    private async Task ExportPng()
    {
        var page = _canvas.Page;
        var nb = _session.Notebook;
        if (page == null || nb == null) return;
        var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = "Exportar página",
            SuggestedFileName = $"{nb.Title} - página {_viewIndex + 1}.png",
            DefaultExtension = "png",
            FileTypeChoices = [new FilePickerFileType("Imagem PNG") { Patterns = ["*.png"] }],
        });
        if (file == null) return;
        await using var s = await file.OpenWriteAsync();
        RenderPagePng(page, s);
    }

    private async Task Info(string title, string text)
    {
        var ok = new Button { Content = "OK", HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        var dlg = new Window
        {
            Title = title, Width = 520, SizeToContent = SizeToContent.Height, CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Content = new StackPanel
            {
                Margin = new Thickness(20),
                Children = { new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap }, ok },
            },
        };
        ok.Click += (_, _) => dlg.Close();
        await dlg.ShowDialog(this);
    }

    // ------------------------------------------------------------------ testes

    /// <summary>
    /// Opções de linha de comando (para testes):
    ///   --open caderno.json        abre um caderno guardado
    ///   --snap saida.png [segs]    grava uma imagem da janela ao fim de N segundos e fecha
    /// </summary>
    private void HandleArgs(string[] args)
    {
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--open" && i + 1 < args.Length)
            {
                try { LoadFile(args[++i]); } catch { }
            }
            else if (args[i] == "--snap" && i + 1 < args.Length)
            {
                var path = args[++i];
                double secs = i + 1 < args.Length && double.TryParse(args[i + 1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : 3;
                DispatcherTimer.RunOnce(() =>
                {
                    var size = new PixelSize((int)Bounds.Width, (int)Bounds.Height);
                    using var rtb = new RenderTargetBitmap(size, new Vector(96, 96));
                    rtb.Render(this);
                    rtb.Save(path);
                    Close();
                }, TimeSpan.FromSeconds(secs));
            }
        }
    }
}
