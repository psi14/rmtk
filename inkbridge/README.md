# InkBridge — mesa de notas tablet → PC

Usa o tablet Android (pensado para o **TCL NXTPAPER 11 Plus** com caneta) como caderno de notas e desenho.
O que escreves aparece **em tempo real** no PC Windows, por **Wi-Fi** ou por **cabo USB**.

```
 Tablet (Android)                              PC (Windows 64)
 ┌───────────────────────┐   Wi-Fi ou USB    ┌───────────────────────┐
 │ InkBridge (caderno)   │ ───────────────►  │ InkBridge.exe         │
 │ caneta, borracha,     │  traços ao vivo   │ mostra a página,      │
 │ páginas, PDF          │  (TCP 47800)      │ guarda, exporta PNG   │
 └───────────────────────┘                   └───────────────────────┘
```

O tablet é o "dono" das notas: guarda tudo localmente e funciona sem PC.
O PC mostra e guarda uma cópia de cada caderno em `Documentos\InkBridge`.

## Instalar

### Tablet
1. Copia `InkBridge.apk` para o tablet e abre-o.
2. Se o Android pedir, permite "Instalar apps desconhecidas" para o gestor de ficheiros.

### PC (Windows 10/11, 64 bits)
1. Copia `InkBridge.exe` para uma pasta (por exemplo `C:\InkBridge`). Não precisa de instalação nem de .NET.
2. Na primeira vez, o **Firewall do Windows** pergunta se permite o acesso:
   marca **Redes privadas** e carrega em **Permitir**. Sem isto, o Wi-Fi não funciona.

## Ligar

### Por Wi-Fi (recomendado)
1. PC e tablet na **mesma rede Wi-Fi**. A rede do Windows deve estar como **Privada**.
2. Abre o `InkBridge.exe` no PC.
3. No tablet, abre um caderno e toca no ícone de ligação (ou em "Sem PC").
4. Escolhe o teu PC na lista. Aparece sozinho ao fim de 1–2 segundos.
   Se não aparecer, usa **IP manual…** com o IP que aparece em baixo na janela do PC.
5. Da próxima vez, o tablet volta a ligar-se sozinho.

### Por cabo USB (atraso mínimo, não precisa de rede)
1. No tablet: **Definições → Acerca do tablet → toca 7× em "Número de compilação"**.
   Depois, em **Opções de programador**, ativa a **Depuração USB**.
2. No PC: transfere o **"SDK Platform-Tools for Windows"** do site oficial do Android (developer.android.com),
   descompacta-o e copia a pasta `platform-tools` para junto do `InkBridge.exe`.
3. Liga o cabo e aceita **"Permitir depuração USB"** no tablet.
4. O PC trata do resto sozinho (corre `adb reverse` a cada 4 s). Também há o botão **Ligar por USB**.
5. No tablet, na janela de ligação, escolhe **Cabo USB**.

## Usar

### Tablet
| Ação | Como |
|---|---|
| Escrever | Caneta (sensível à pressão) |
| Borracha rápida | Botão lateral da caneta carregado enquanto escreves |
| Borracha parcial ↔ de traço inteiro | Toca outra vez no botão da borracha |
| Mover a página | 1 dedo |
| Zoom | 2 dedos |
| Desfazer / Refazer | Toque rápido com 2 dedos / com 3 dedos (ou os botões) |
| Palma da mão | Ignorada: os dedos não desenham (podes mudar em ⋮ → "Desenhar com o dedo") |
| Páginas | ◀ ▶, "+" para página nova (▶ na última página também cria uma) |
| Fundo | Em branco, pautado, quadriculado, pontos |
| Formato | A4 vertical, A4 horizontal, ecrã 16:10 (⋮ → Formato da página) |
| Exportar | ⋮ → Exportar caderno para PDF (vetorial) |
| Cadernos | Ecrã inicial: criar; toque longo para mudar o nome ou apagar |

### PC
- **Seguir o tablet**: mostra sempre a página em que estás a escrever (tecla `F` volta a ligar).
- **◀ ▶ / setas do teclado**: ver outras páginas.
- **Roda do rato**: zoom. **Arrastar**: mover. **Duplo clique**: ajustar.
- **Ajustar à largura**, **Ecrã inteiro (F11)** (bom para projetar numa aula), **Sempre visível**.
- **Exportar PNG** da página, **Abrir…** um caderno guardado (funciona sem tablet).
- O círculo azul mostra onde a caneta está a pairar sobre o ecrã do tablet.

## O que já existe e o que falta (fases)

**Feito (fase 1 + 2)**
- Caneta com pressão, marcador, borracha parcial e de traço inteiro, desfazer/refazer
- Rejeição da palma, zoom e arrasto com dedos, gestos de 2 e 3 dedos
- Baixa latência no tablet: eventos não agrupados, previsão de movimento, desenho incremental
- Vários cadernos, páginas, 4 fundos, 3 formatos, exportação PDF
- Tempo real no PC por Wi-Fi (descoberta automática) e por USB (adb reverse automático)
- PC guarda cópia dos cadernos, abre-os sem tablet, exporta PNG, ecrã inteiro

**Próximas fases**
- Laço para selecionar, mover e redimensionar traços
- Importar PDF e anotar por cima
- Cores personalizadas, mais espessuras e canetas (lápis, caligrafia)
- Formas automáticas (linha reta, círculo, retângulo ao segurar a caneta)
- Pesquisa, pastas, miniaturas das páginas
- Exportar PDF também no PC; o PC enviar alterações de volta ao tablet

## Compilar a partir do código

```bash
# Android (precisa de JDK 17+ e Android SDK 35)
cd inkbridge/android
./gradlew testDebugUnitTest assembleRelease      # → app/build/outputs/apk/release/app-release.apk

# Windows (precisa de .NET 8 SDK; compila também a partir de Linux)
cd inkbridge/windows
dotnet publish InkBridge.Desktop -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o out
```

O fluxo `.github/workflows/inkbridge.yml` compila os dois automaticamente em cada push e deixa
o APK e o EXE para descarregar em **Actions → InkBridge → Artifacts**.

Formato das mensagens entre tablet e PC: ver [PROTOCOL.md](PROTOCOL.md).

## Problemas comuns
- **O PC não aparece na lista do tablet**: a rede do Windows tem de estar como **Privada**, e o Firewall tem de
  permitir o InkBridge. Alguns routers (redes de convidados, eduroam) bloqueiam a comunicação entre aparelhos:
  usa o cabo USB ou o hotspot do telemóvel.
- **"adb não encontrado"**: falta a pasta `platform-tools` junto do `InkBridge.exe`.
- **USB não liga**: confirma que aceitaste "Permitir depuração USB" no tablet e que o cabo transmite dados
  (há cabos que só carregam).
