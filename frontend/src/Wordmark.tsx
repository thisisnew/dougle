export default function Wordmark() {
  return <span className="dougle-wordmark" role="img" aria-label="Dougle">
    {[...'Dougle'].map((letter, index) => <span key={index} aria-hidden="true">{letter}</span>)}
  </span>
}
