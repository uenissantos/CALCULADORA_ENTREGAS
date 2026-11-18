# Calculadora de Entregas (Android)

Projeto inicial Android nativo em Kotlin.

## O que contém
- Leitura de texto pela árvore de acessibilidade.
- Captura de tela pelo `AccessibilityService` em Android 11/API 30 ou superior, quando o sistema permitir.
- OCR local com Google ML Kit.
- Card flutuante usando `TYPE_APPLICATION_OVERLAY`.
- Cálculo com mínimo estático de R$ 2,00/km.
- Parser de moeda brasileira e distâncias em km.
- Tela inicial com atalhos para as permissões e teste da fórmula.

## Requisitos
- Android Studio recente.
- JDK 17.
- Android SDK 35 instalado.
- Um aparelho Android para testar permissões e captura real.

## Abrir e compilar
1. Extraia o ZIP.
2. Abra a pasta `CalculadoraEntregas` no Android Studio.
3. Aguarde a sincronização do Gradle.
4. Instale em um aparelho físico.
5. Abra o app e conceda "Permitir sobreposição".
6. Em Configurações > Acessibilidade, ative "Leitor de ofertas".
7. Abra o aplicativo de entregas e observe se o card aparece quando a oferta contém pagamento e duas distâncias.

## Limitações conhecidas desta versão
- O reconhecimento usa os dois primeiros valores `km` encontrados no texto. Alguns aplicativos podem ordenar ou rotular distâncias de forma diferente; valide em telas reais antes de confiar no resultado.
- A captura via AccessibilityService depende de API 30+ e pode falhar em telas protegidas ou em aplicativos que bloqueiam captura.
- O Android não oferece prioridade absoluta para ficar acima de toda janela ou sobreposição.
- O OCR é executado localmente. A captura não é iniciada sem o serviço de acessibilidade habilitado.
- Esta é uma versão inicial para testes, não uma versão validada em todos os fabricantes ou aplicativos de entrega.
- O card não tem controles de arrastar/fechar nesta primeira versão.
- Não há detecção robusta de identificador único da oferta; a deduplicação inicial usa valor e distâncias.
- O serviço de overlay é iniciado quando uma oferta válida é reconhecida. Restrições do fabricante/Android podem impedir sua inicialização em segundo plano.

## Privacidade
A acessibilidade e a captura de tela podem expor informações visíveis de outros aplicativos. Ative apenas se compreender e concordar com isso. O projeto não envia imagens nem textos para um servidor; o OCR é processado no aparelho.
"# CALCULADORA_ENTREGAS" 
