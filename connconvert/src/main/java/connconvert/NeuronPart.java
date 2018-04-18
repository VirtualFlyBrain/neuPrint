package connconvert;

public class NeuronPart {
    // number of pre and post terminals within roi
    public int pre;
    public int post;
    public String roi;

    public NeuronPart(String roi, int pre, int post) {
        this.roi = roi;
        this.pre = pre;
        this.post = post;
    }

    @Override
    public String toString() {
        return roi + " : pre=" + pre + " post=" + post;
    }

    public String getRoi(){
        return this.roi;
    }

    public int getPre() {
        return this.pre;
    }

    public int getPost() {
        return this.post;
    }


}
