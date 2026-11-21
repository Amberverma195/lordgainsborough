import * as https from 'https';
import { getLegalDocumentUrl, PRODUCTION_VERCEL_LEGAL_ORIGIN } from '../legal';

const ANDROID_USER_AGENT =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36';

const IOS_USER_AGENT =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1';

function fetchUrl(url: string, userAgent: string): Promise<{ statusCode: number; headers: Record<string, string | string[] | undefined>; body: string }> {
  return new Promise((resolve, reject) => {
    const req = https.get(
      url,
      {
        headers: {
          'User-Agent': userAgent,
          Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        },
        timeout: 10000,
      },
      (res) => {
        let data = '';
        res.on('data', (chunk) => {
          data += chunk;
        });
        res.on('end', () => {
          resolve({
            statusCode: res.statusCode || 0,
            headers: res.headers,
            body: data,
          });
        });
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('Request timed out'));
    });
    req.on('error', (err) => {
      reject(err);
    });
  });
}

describe('Production Legal Document URLs Reachability (Android & iOS)', () => {
  it('resolves canonical production origin to live Vercel deployment', () => {
    expect(PRODUCTION_VERCEL_LEGAL_ORIGIN).toBe('https://lordgainsborough.vercel.app');
    expect(getLegalDocumentUrl('/terms')).toBe('https://lordgainsborough.vercel.app/terms');
    expect(getLegalDocumentUrl('/privacy')).toBe('https://lordgainsborough.vercel.app/privacy');
  });

  describe('Android Mobile Device Emulation', () => {
    it('returns HTTP 200 with valid Terms of Service HTML for Android user-agent', async () => {
      const url = getLegalDocumentUrl('/terms');
      const response = await fetchUrl(url, ANDROID_USER_AGENT);

      expect(response.statusCode).toBe(200);
      expect(response.headers['content-type']).toContain('text/html');
      expect(response.body).toContain('Terms of Service');
      expect(response.body).toContain('Lord Gainsborough');
    }, 15000);

    it('returns HTTP 200 with valid Privacy Policy HTML for Android user-agent', async () => {
      const url = getLegalDocumentUrl('/privacy');
      const response = await fetchUrl(url, ANDROID_USER_AGENT);

      expect(response.statusCode).toBe(200);
      expect(response.headers['content-type']).toContain('text/html');
      expect(response.body).toContain('Privacy Policy');
      expect(response.body).toContain('Lord Gainsborough');
    }, 15000);
  });

  describe('iOS Mobile Device Emulation', () => {
    it('returns HTTP 200 with valid Terms of Service HTML for iOS Mobile Safari user-agent', async () => {
      const url = getLegalDocumentUrl('/terms');
      const response = await fetchUrl(url, IOS_USER_AGENT);

      expect(response.statusCode).toBe(200);
      expect(response.headers['content-type']).toContain('text/html');
      expect(response.body).toContain('Terms of Service');
      expect(response.body).toContain('Lord Gainsborough');
    }, 15000);

    it('returns HTTP 200 with valid Privacy Policy HTML for iOS Mobile Safari user-agent', async () => {
      const url = getLegalDocumentUrl('/privacy');
      const response = await fetchUrl(url, IOS_USER_AGENT);

      expect(response.statusCode).toBe(200);
      expect(response.headers['content-type']).toContain('text/html');
      expect(response.body).toContain('Privacy Policy');
      expect(response.body).toContain('Lord Gainsborough');
    }, 15000);
  });
});
