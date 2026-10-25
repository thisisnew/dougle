import mascotImage from './assets/dougle-mascot-transparent.png'

export default function Wordmark({ mascot = true }: { mascot?: boolean }) {
  return <span className="dougle-wordmark" role="img" aria-label="Dougle">
    {[...'Dougle'].map((letter, index) => <span key={index} aria-hidden="true" className={mascot && index === 1 ? 'wordmark-face' : undefined}>{mascot && index === 1 ? <><span className="sr-only">{letter}</span><img src={mascotImage} alt="" draggable={false} /></> : letter}</span>)}
  </span>
}
