# Protocolo InkBridge (v1)

- **Descoberta**: o PC envia a cada 1 s um UDP broadcast para a porta **47801**:
  `{"t":"beacon","name":"NOME-DO-PC","port":47800,"v":1}`
- **Ligação**: o tablet liga-se por TCP à porta **47800** do PC. Por USB, `adb reverse tcp:47800 tcp:47800`
  e o tablet liga-se a `127.0.0.1:47800`.
- Cada mensagem é **uma linha de JSON** terminada em `\n` (UTF-8). `TCP_NODELAY` ligado.
- Coordenadas em **unidades da página**: uma página A4 vertical tem 1000 × 1414.
- Pontos: lista plana `[x, y, pressão, x, y, pressão, …]`, pressão de 0 a 1.
- Cores: inteiro ARGB de 32 bits com sinal (como no Android), por exemplo `-16777216` = preto opaco.
- Largura real de um ponto de caneta: `w × (0,35 + 0,65 × pressão)`. O marcador (`"k":"hl"`) tem largura fixa
  e desenha-se com alfa `0x60`.

## Tablet → PC

| `t` | Campos | Significado |
|---|---|---|
| `hello` | `device`, `v` | Primeira mensagem |
| `doc` | `nb` (caderno completo) | Estado inteiro; enviado ao ligar e quando muda a estrutura (páginas, fundo, título) |
| `cur` | `i` | Página atual no tablet |
| `sb` | `pg`, `id`, `k` (`pen`/`hl`), `c`, `w` | Início de traço |
| `sp` | `id`, `pts` | Mais pontos do traço em curso |
| `se` | `id` | Fim de traço |
| `sc` | `id` | Traço cancelado (por exemplo, a palma tocou primeiro) |
| `del` | `pg`, `ids` | Traços apagados (borracha, desfazer) |
| `add` | `pg`, `strokes` | Traços acrescentados (borracha parcial, refazer) |
| `hv` | `x`, `y`, `on` | Caneta a pairar sobre o ecrã |
| `ping` | — | A cada 2 s sem tráfego. O PC responde `pong` |

## PC → Tablet

| `t` | Campos |
|---|---|
| `welcome` | `name` (nome do PC) |
| `pong` | — |

## Formato do caderno (ficheiro e mensagem `doc`)

```json
{"id":"a1b2c3","title":"Estruturas","cur":0,"modified":1731340000000,
 "pages":[{"id":"p1","tpl":"lined","w":1000,"h":1414,
           "strokes":[{"id":"s1","k":"pen","c":-16777216,"w":3,"pts":[100,200,0.5, 101,203,0.6]}]}]}
```
`tpl` pode ser `blank`, `lined`, `grid` ou `dots`.
