# Formulários públicos: Contato e Feedback

`POST /contact` e `POST /feedback` aceitam JSON sem autenticação:

```json
{
  "name": "Ana",
  "email": "ana@example.com",
  "subject": "outro",
  "subjectOther": "Meu assunto",
  "message": "Primeira linha.\nSegunda linha."
}
```

Todos os valores usados são normalizados com `strip()` antes da validação
de obrigatoriedade e tamanho. `name`, `email`, `subject` e `message` são
obrigatórios. Limites em unidades UTF-16 (Java e JavaScript): nome 100, e-mail 254,
código de assunto 32, assunto personalizado 120, mensagem 3000.
O e-mail precisa ter formato válido; isso não comprova a titularidade.
Caracteres de controle e separadores de linha são rejeitados nos campos de
cabeçalho, inclusive antes da normalização. As quebras internas da mensagem
são preservadas. `subjectOther` é obrigatório somente para `outro`; nos
demais casos seu conteúdo é ignorado. Campos extras não definem remetente
ou destinatário e são ignorados pelo desserializador padrão.

| Formulário | Código | Rótulo do frontend |
| --- | --- | --- |
| Contato | faq | Não encontrei minha dúvida na FAQ |
| Contato | junta | Dúvida sobre o Junta.ai |
| Contato | sugestao | Sugestão ou ideia |
| Contato | parceria | Parceria ou colaboração |
| Contato | imprensa | Imprensa |
| Contato | outro | Texto de subjectOther |
| Feedback | experiencia | Experiência com o Junta.ai |
| Feedback | sugestao | Sugestão ou nova ideia |
| Feedback | problema | Encontrei um problema |
| Feedback | duvida | Algo não ficou claro |
| Feedback | privacidade | Privacidade e segurança |
| Feedback | financeiro | Recursos financeiros |
| Feedback | assistente | Assistente / IA |
| Feedback | outro | Texto de subjectOther |

Assuntos dos e-mails: `[Contato Junta.ai] <rótulo>` ou
`[Feedback Junta.ai] <rótulo>`. O texto simples UTF-8 contém origem, nome,
e-mail, assunto e mensagem. O destinatário vem exclusivamente de
`app.mail.contact-to`; remetente de `MAIL_FROM`; `Reply-To` do e-mail validado.
Não há persistência de mensagens, tabelas ou migrations novas.

Após `JavaMailSender.send` concluir, a API retorna **200 `{"status":"sent"}`**:
aceitação pelo SMTP, sem confirmação de entrega na caixa postal.
Validação/JSON inválido: **400**; corpo HTTP excessivo: **413**; cota excedida: **429**;
configuração ausente/inválida, capacidade ocupada ou falha de envio: **503**.
Erros tratados pelo handler global incluem `timestamp`, `status` numérico,
`erro`, `mensagem` e, quando aplicável, `campos`.
Há dois caminhos existentes para HTTP 413: a rejeição antecipada pelo filtro,
quando `Content-Length` excede o teto, responde diretamente sem `timestamp`:

```json
{"status":413,"erro":"Payload Too Large","mensagem":"Requisição grande demais."}
```

Quando o excesso é detectado durante a leitura, a exceção é tratada pelo
handler global e a resposta inclui também `timestamp`. Ambos os caminhos
retornam 413 com os mesmos `status`, `erro` e `mensagem`; o frontend usa o
status HTTP e não depende da presença de `timestamp`.
Não retornamos nem registramos o conteúdo ou as exceções SMTP.
O modo console local/dev continua disponível para OTP, mas formulários
retornam 503 quando não há SMTP real configurado. A política atual do OTP
continua impedindo a inicialização com SMTP incompleto fora de local/dev.
Ausência de `CONTACT_EMAIL_TO` não impede o OTP; indisponibiliza formulários.

