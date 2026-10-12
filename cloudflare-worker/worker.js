export default {
  async fetch(request, env) {
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    if (request.method !== 'POST') {
      return new Response('Method not allowed', { status: 405, headers: corsHeaders });
    }

    try {
      const body = await request.json();
      const { title, description, contactName } = body;

      const notifTitle = `📋 Nova solicitação — ${contactName || 'Contato'}`;
      const notifBody  = title + (description ? '\n' + description : '');

      const response = await fetch('https://onesignal.com/api/v1/notifications', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Key ${env.ONESIGNAL_API_KEY}`,
        },
        body: JSON.stringify({
          app_id: env.ONESIGNAL_APP_ID,
          included_segments: ['All'],
          headings: { en: notifTitle },
          contents: { en: notifBody },
          priority: 10,
        }),
      });

      const result = await response.json();
      return new Response(JSON.stringify(result), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    } catch (e) {
      return new Response(JSON.stringify({ error: e.message }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }
  }
};
