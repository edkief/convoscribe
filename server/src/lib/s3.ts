import { S3Client } from '@aws-sdk/client-s3';
import { env } from './env';

let client: S3Client | undefined;

/** The S3-compatible blob store; objects go under the bucket in S3_BUCKET. */
export function s3(): S3Client {
  if (!client) {
    const config = env();
    client = new S3Client({
      endpoint: config.S3_ENDPOINT,
      region: config.S3_REGION,
      forcePathStyle: config.S3_FORCE_PATH_STYLE,
      credentials: {
        accessKeyId: config.S3_ACCESS_KEY_ID,
        secretAccessKey: config.S3_SECRET_ACCESS_KEY,
      },
    });
  }
  return client;
}
