# Calculadora de Entregas

App Android que lê o card de uma corrida/entrega que está na tela do celular e mostra, por cima do app de corrida, um card com o cálculo (valor por km, valor por hora e lucro) para ajudar a decidir se a corrida compensa.

Versão atual: **v11**

## Como funciona

O app usa um serviço de acessibilidade para tirar uma foto da tela, lê os textos da foto (OCR no próprio aparelho, sem internet) e calcula em cima do **valor da rota**, dos **KM** e do **tempo**. O resultado aparece em um card flutuante, que some sozinho depois de alguns segundos.

Existem duas formas de o app achar esses dados na tela:

1. **Leitura por texto**: procura valor, KM e minutos nos textos da tela. Funciona sem treino, mas depende do layout do app de corrida.
2. **Leitura por modelo treinado**: você marca no print onde fica cada item. Funciona em qualquer app de corrida, porque o app sabe exatamente onde olhar.

**Regra de prioridade:** se existir pelo menos um modelo treinado salvo, **só o modelo vale**. Se o modelo não conseguir ler a tela, o app diz que não achou corrida e não tenta adivinhar pela leitura por texto. Sem nenhum modelo salvo, usa a leitura por texto.

## Requisitos

- Android 11 (API 30) ou mais novo. A leitura por foto usa a captura de tela do serviço de acessibilidade, que só existe a partir dessa versão.
- Permissão de acessibilidade ativada para o app.
- Alguns apps bloqueiam captura de tela. Nesses casos o diagnóstico mostra "código 6" e a leitura por foto não funciona.

## Instalação e build

O projeto compila pelo GitHub Actions (arquivo `.github/workflows/build-apk.yml`):

1. Envie o projeto para um repositório no GitHub (branch `main` ou `master`).
2. O workflow roda sozinho a cada envio, ou manualmente em **Actions > Build APK > Run workflow**.
3. Baixe o APK em **Artifacts** (`CalculadoraEntregas-debug`) e instale no celular.

Build local: JDK 17 e `gradle :app:assembleDebug` (Gradle 8.9).

## Primeiro uso

1. Abra o app e toque para ativar o serviço de acessibilidade.
2. Na tela principal, ajuste os valores usados no cálculo e salve.
3. Um botão flutuante aparece na tela. Ele começa **desligado**.
4. Abra o app de corrida e use o botão flutuante (veja abaixo).

## Botão flutuante

O botão principal pode ser arrastado pela tela. Ao tocar nele, quatro ícones abrem em arco ao redor dele:

| Ícone | Função |
|---|---|
| Ligar/desligar | Liga ou desliga a **leitura automática**. Verde quando ligada. |
| Calculadora | Lê a tela **agora** e mostra o card. Funciona mesmo com a leitura automática desligada. |
| Câmera | Tira um **print real** da tela e guarda no app (em Artefatos) para usar no treino. |
| X | **Tira o botão da tela** e desliga a leitura. |

Para trazer o botão de volta, abra o app e toque em **Mostrar botão flutuante**. Ele volta com a leitura automática desligada.

## Treinar (modelos)

Na tela principal, toque em **Treinar**.

1. Escolha a imagem: da **galeria** ou dos **prints guardados no app** (Artefatos). Use um print da tela inteira com o card da corrida aparecendo.
2. Arraste um retângulo sobre um item da imagem. Aparece um flutuante para dizer o que ele é:
   - **Valor** (valor da rota)
   - **KM**: único, ida ou volta
   - **Tempo**: único, ida ou volta
3. Use **único** quando o card já mostra o total somado. Se o app mostra ida e volta separados, marque os dois e o app soma.
4. O que você não marcar fica em branco.
5. Tocar em uma marcação já feita reabre o flutuante, para trocar o tipo ou apagar só aquela.
6. Toque no ✓ (embaixo, à direita). O app mostra o que leu em cada área e o cálculo. Se estiver certo, toque em **Salvar** e escolha o **nome do modelo**.

Outros ícones da tela de treino:

- 🖼 troca a imagem
- 📋 abre os modelos salvos
- 💾 backup e restauração
- 🗑 (embaixo, à esquerda) apaga as marcações da tela, ou o modelo salvo que estiver aberto

Detalhes:

- Salvar com o **mesmo nome** de um modelo aberto substitui; com outro nome, cria um modelo novo.
- A **imagem de treino é copiada para dentro do app**. Ela continua disponível mesmo se você apagar da galeria.
- As marcações são guardadas em proporção da tela, então valem em qualquer resolução, desde que o print de treino seja da tela inteira.
- Com vários modelos salvos, o app tenta um por um e usa o primeiro que ler valor e KM válidos.
- A leitura só é aceita com valor até R$ 5.000 e KM até 400, para não confundir outras telas com corrida.

### Backup e restauração

No ícone 💾 da tela de treino:

- **Fazer backup**: salva um arquivo `.zip` com todos os modelos e suas imagens, no lugar que você escolher.
- **Restaurar backup**: abre um `.zip` de backup e recupera os modelos e as imagens. Modelos com o mesmo nome são substituídos.

Assim, quando você validar um treino, não precisa retreinar o app depois de uma reinstalação.

## Artefatos

Na tela principal, toque em **Artefatos**. Cada artefato guarda:

- o **print** (o que o app viu) e
- a **leitura** (o que o app leu), com a posição de cada texto no print.

Os dois são ligados pelo mesmo identificador e pelos números das caixas.

Um artefato é criado quando:

- você usa a **câmera** do botão flutuante (só o print, para treinar);
- você usa a **calculadora** e o app **não acha corrida** (print + leitura, para entender o motivo);
- a opção **Guardar também as leituras certas** está ligada e a calculadora leu uma corrida (print + leitura, para conferir se o valor bate com a tela). Vem desligada.

Ao abrir um artefato:

- **Ver o que foi lido** mostra o print com caixas numeradas em cada texto lido, as áreas procuradas pelos modelos treinados (em azul) e, embaixo, a lista escrita do que foi lido.
- **Compartilhar** envia a imagem comentada, o print original e o texto da leitura.
- **Apagar** remove o artefato.

Na lista, **Compartilhar tudo** envia um `.zip` com todos os artefatos e **Apagar tudo** limpa a lista. O app guarda no máximo 150 artefatos e apaga os mais antigos.

## Consumo de bateria

- **Desligado**: o serviço de acessibilidade não recebe nenhum evento do sistema. Fica apenas o botão na tela, com consumo praticamente zero.
- Os botões **calculadora** e **câmera** gastam bateria só no momento do toque.
- **Ligado**: os eventos são agrupados e a leitura roda no máximo uma vez a cada 0,9 segundo. Se a tela não mudou desde a leitura anterior, o app pula o OCR.
- Quando você terminar de trabalhar, toque no **X** do botão flutuante para remover o botão e desligar tudo.

## Diagnóstico

Na tela principal há um botão de diagnóstico com o estado da última leitura (eventos recebidos, resultado da captura de tela, textos lidos, última leitura feita pela calculadora). Use-o junto com os Artefatos para entender por que uma corrida não foi encontrada.

## Estrutura do projeto

```
app/src/main/java/com/calculadora/entregas/
  MainActivity.kt          tela principal e configurações
  AccessibilityReader.kt   serviço: leitura automática, calculadora e câmera
  OverlayUi.kt             botão flutuante, ícones, card e linha de leitura
  OcrReader.kt             OCR (texto e posição de cada palavra)
  OfferParser.kt           leitura por texto (sem modelo)
  OfferCalculator.kt       cálculo e formatação
  Template.kt              modelos treinados, leitura por áreas, backup e restauração
  TrainActivity.kt         tela de treino
  Artifacts.kt             armazenamento, imagem comentada e compartilhamento
  ArtifactsActivity.kt     lista e detalhe dos artefatos
  AppConfig.kt             configurações salvas
  DebugLog.kt              diagnóstico
  BuildInfo.kt             versão e cor do botão
```

## Privacidade

Tudo roda no aparelho: foto da tela, OCR, modelos e artefatos ficam no armazenamento interno do app. Nada é enviado pela internet. O compartilhamento só acontece quando você escolhe compartilhar um artefato ou um backup.
