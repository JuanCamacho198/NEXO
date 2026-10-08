import { z } from 'zod';

export type ValidationKey =
  | 'import.validation.invalidInput'
  | 'import.validation.emptyBatch'
  | 'import.validation.invalidItem'
  | 'import.validation.invalidMetadata';

export type ValidationFailure = {
  ok: false;
  key: ValidationKey;
  fallback: string;
};

export type ValidationSuccess<T> = {
  ok: true;
  data: T;
};

export type ValidationResult<T> = ValidationSuccess<T> | ValidationFailure;

export class ValidationError extends Error {
  readonly key: ValidationKey;
  readonly fallback: string;
  readonly index?: number;

  constructor(key: ValidationKey, fallback: string, index?: number) {
    super(fallback);
    this.name = 'ValidationError';
    this.key = key;
    this.fallback = fallback;
    if (index !== undefined) {
      this.index = index;
    }
  }
}

const formatSchema = z.preprocess(
  (value) => (typeof value === 'string' ? value.trim().toLowerCase() : value),
  z.enum(['epub', 'pdf']),
);

const bookImportInputSchema = z.object({
  sourcePath: z.string().trim().min(1),
  title: z.string().trim().min(1).optional(),
  author: z.string().trim().min(1).optional(),
  format: formatSchema,
  genre: z.string().trim().max(80).nullable().optional(),
});

export type BookImportInputData = z.infer<typeof bookImportInputSchema>;

export type BulkImportBatchData = BookImportInputData[];

const optionalDateString = z
  .string()
  .trim()
  .optional()
  .refine((value) => value === undefined || !Number.isNaN(Date.parse(value)), {
    message: 'Invalid date string',
  });

const epubMetadataSchema = z
  .object({
    title: z.string().trim().min(1).nullable().optional(),
    author: z.string().trim().min(1).nullable().optional(),
    creator: z.string().trim().min(1).nullable().optional(),
    creators: z.array(z.string().trim().min(1)).optional(),
    subject: z.string().trim().min(1).nullable().optional(),
    subjects: z.array(z.string()).optional(),
    identifiers: z.array(z.string().trim().min(1)).optional(),
    language: z
      .string()
      .trim()
      .regex(/^[a-zA-Z]{2}(-[a-zA-Z]{2,4})?$/)
      .nullable()
      .optional(),
    coverRef: z.string().trim().min(1).nullable().optional(),
    publishedAt: optionalDateString,
    date: optionalDateString,
  })
  .refine(
    (value) => {
      const candidates = [value.title, value.author, value.creator, ...(value.creators ?? [])];
      return candidates.some((candidate) => typeof candidate === 'string' && candidate.length > 0);
    },
    { message: 'Missing title/author' },
  );

export type EpubMetadataData = z.infer<typeof epubMetadataSchema>;

export function validateBookImportInput(input: unknown): ValidationResult<BookImportInputData> {
  const parsed = bookImportInputSchema.safeParse(input);
  if (parsed.success) {
    return { ok: true, data: parsed.data };
  }
  return {
    ok: false,
    key: 'import.validation.invalidInput',
    fallback: 'Import input is invalid. Verify the file path and format.',
  };
}

export type BulkItemResult<T> =
  | { ok: true; index: number; data: T }
  | { ok: false; index: number; key: ValidationKey; fallback: string };

export function validateBulkImportBatch(items: unknown): {
  ok: boolean;
  key?: ValidationKey;
  fallback?: string;
  results: BulkItemResult<BookImportInputData>[];
} {
  if (!Array.isArray(items)) {
    return {
      ok: false,
      key: 'import.validation.emptyBatch',
      fallback: 'No files to import yet.',
      results: [],
    };
  }
  if (items.length === 0) {
    return {
      ok: false,
      key: 'import.validation.emptyBatch',
      fallback: 'No files to import yet.',
      results: [],
    };
  }
  const results: BulkItemResult<BookImportInputData>[] = items.map(
    (item, index): BulkItemResult<BookImportInputData> => {
      const parsed = bookImportInputSchema.safeParse(item);
      if (parsed.success) {
        return { ok: true, index, data: parsed.data };
      }
      return {
        ok: false,
        index,
        key: 'import.validation.invalidItem',
        fallback: `Item ${index + 1} is invalid and was skipped.`,
      };
    },
  );
  return { ok: results.every((result) => result.ok), results };
}

export function validateEpubMetadata(dto: unknown): ValidationResult<EpubMetadataData> {
  const parsed = epubMetadataSchema.safeParse(dto);
  if (parsed.success) {
    return { ok: true, data: parsed.data };
  }
  return {
    ok: false,
    key: 'import.validation.invalidMetadata',
    fallback: 'Book metadata is incomplete and was skipped.',
  };
}
