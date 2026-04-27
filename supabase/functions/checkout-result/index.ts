Deno.serve((req) => {
  const url = new URL(req.url);
  const status = url.searchParams.get("status") ?? "unknown";
  const success = status === "success";

  const html = `<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>PromoTracker</title>
  <style>
    body { font-family: system-ui, sans-serif; display: flex; justify-content: center;
           align-items: center; min-height: 100vh; margin: 0; background: #f5f5f5; }
    .card { text-align: center; padding: 48px; background: #fff;
            border-radius: 16px; box-shadow: 0 2px 8px rgba(0,0,0,.1); max-width: 400px; }
    h1 { font-size: 24px; margin: 16px 0 8px; }
    p { color: #666; }
  </style>
</head>
<body>
  <div class="card">
    <h1>${success ? "Pagamento confirmado!" : "Pagamento cancelado"}</h1>
    <p>${success
      ? "Sua assinatura Premium está ativa. Volte para o app PromoTracker."
      : "Nenhuma cobrança foi realizada. Volte ao app para tentar novamente."
    }</p>
  </div>
</body>
</html>`;

  return new Response(html, {
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
});
