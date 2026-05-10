import { post } from './client'
import type {
  ReflectRequest,
  ReflectionReport,
  QualityAssessment,
  DetectedError,
} from '../types'

export function reflect(req: ReflectRequest): Promise<ReflectionReport> {
  return post<ReflectionReport>('/api/reflect/reflect', req)
}

export function evaluateQuality(
  body: Record<string, unknown>,
): Promise<QualityAssessment> {
  return post<QualityAssessment>('/api/reflect/evaluate', body)
}

export function detectErrors(
  body: Record<string, unknown>,
): Promise<DetectedError[]> {
  return post<DetectedError[]>('/api/reflect/detect-errors', body)
}
